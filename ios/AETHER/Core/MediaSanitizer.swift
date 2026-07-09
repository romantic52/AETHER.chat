import Foundation
import ImageIO
import AVFoundation
import UniformTypeIdentifiers

// Полная зачистка метаданных перед отправкой — анонимность превыше всего:
//  • фото — без перекодирования (lossless): копируем пиксели через ImageIO,
//    затирая EXIF/GPS/TIFF/IPTC/MakerApple (геолокация, серийники, модель камеры);
//  • видео — экспорт passthrough (без переупаковки дорожек) с фильтром
//    AVMetadataItemFilter.forSharing() — вырезает location/автора/устройство;
//  • прочие форматы файлов трогать нельзя (сломаем содержимое) — шлём как есть.
enum MediaSanitizer {
    /// Убирает метаданные из изображения без потери качества.
    /// Если формат ImageIO не понимает — возвращает исходные байты.
    static func strippedImage(_ data: Data) -> Data {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source) else { return data }
        let count = CGImageSourceGetCount(source)
        let output = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(output, type, count, nil) else { return data }
        let removal: [CFString: Any] = [
            kCGImagePropertyExifDictionary: kCFNull,
            kCGImagePropertyGPSDictionary: kCFNull,
            kCGImagePropertyTIFFDictionary: kCFNull,
            kCGImagePropertyIPTCDictionary: kCFNull,
            kCGImagePropertyMakerAppleDictionary: kCFNull,
        ]
        for index in 0..<count {
            CGImageDestinationAddImageFromSource(dest, source, index, removal as CFDictionary)
        }
        guard CGImageDestinationFinalize(dest) else { return data }
        return output as Data
    }

    /// СЖИМАЕТ видео для обычной отправки: перекодирование в 1280×720 H.264
    /// (как «сжатая» отправка Telegram) + зачистка метаданных. Экономит место
    /// в разы против оригинала с камеры (4K/HEVC).
    static func compressedVideo(_ data: Data, fileExtension: String = "mp4") async -> Data {
        await exportVideo(data, fileExtension: fileExtension, preset: AVAssetExportPreset1280x720)
    }

    /// Убирает метаданные из видео (passthrough — дорожки не перекодируются).
    /// При любой ошибке возвращает исходные байты: лучше отправить, чем потерять.
    static func strippedVideo(_ data: Data, fileExtension: String = "mp4") async -> Data {
        await exportVideo(data, fileExtension: fileExtension, preset: AVAssetExportPresetPassthrough)
    }

    private static func exportVideo(_ data: Data, fileExtension: String, preset: String) async -> Data {
        let dir = FileManager.default.temporaryDirectory
        let input = dir.appendingPathComponent("sanitize_\(UUID().uuidString).\(fileExtension)")
        let output = dir.appendingPathComponent("sanitize_\(UUID().uuidString)_out.mp4")
        defer {
            try? FileManager.default.removeItem(at: input)
            try? FileManager.default.removeItem(at: output)
        }
        do {
            try data.write(to: input)
            let asset = AVURLAsset(url: input)
            guard let export = AVAssetExportSession(asset: asset, presetName: preset) else {
                return data
            }
            export.outputURL = output
            export.outputFileType = .mp4
            export.metadata = []
            export.metadataItemFilter = .forSharing()
            await export.export()
            guard export.status == .completed, let clean = try? Data(contentsOf: output) else { return data }
            // Перекодирование могло вырасти (уже сильно сжатый исходник) — берём меньшее.
            return clean.count < data.count || preset == AVAssetExportPresetPassthrough ? clean : data
        } catch {
            return data
        }
    }

    /// Универсальная точка: чистим то, что умеем, по mime-типу.
    static func sanitize(data: Data, mime: String) async -> Data {
        if mime.hasPrefix("image/") { return strippedImage(data) }
        if mime.hasPrefix("video/") {
            let ext = UTType(mimeType: mime)?.preferredFilenameExtension ?? "mp4"
            return await strippedVideo(data, fileExtension: ext)
        }
        return data
    }
}


// ---- Кружки: склейка сегментов дозаписи и обрезка ----

extension MediaSanitizer {
    /// Склеивает видео-сегменты (дозапись кружка) в один mp4 (720p, метаданные
    /// вычищены). Для одного сегмента без обрезки — тоже сюда: заодно унифицирует
    /// контейнер. Возвращает URL временного файла (вызывающий удаляет).
    static func mergeClips(_ urls: [URL]) async -> URL? {
        guard !urls.isEmpty else { return nil }
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { return nil }
        let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)

        var cursor = CMTime.zero
        for url in urls {
            let asset = AVURLAsset(url: url)
            guard let srcVideo = try? await asset.loadTracks(withMediaType: .video).first,
                  let duration = try? await asset.load(.duration) else { continue }
            let range = CMTimeRange(start: .zero, duration: duration)
            try? videoTrack.insertTimeRange(range, of: srcVideo, at: cursor)
            if let srcAudio = try? await asset.loadTracks(withMediaType: .audio).first {
                try? audioTrack?.insertTimeRange(range, of: srcAudio, at: cursor)
            }
            // Сохранить ориентацию/зеркало первого сегмента.
            if cursor == .zero, let transform = try? await srcVideo.load(.preferredTransform) {
                videoTrack.preferredTransform = transform
            }
            cursor = CMTimeAdd(cursor, duration)
        }
        guard cursor > .zero,
              let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPreset1280x720) else { return nil }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("circle_merged_\(UUID().uuidString).mp4")
        export.outputURL = out
        export.outputFileType = .mp4
        export.metadata = []
        export.metadataItemFilter = .forSharing()
        await export.export()
        guard export.status == .completed else { return nil }
        return out
    }

    /// Обрезка готового файла по диапазону (passthrough — без перекодирования).
    static func trimmedClip(url: URL, start: TimeInterval, end: TimeInterval) async -> Data? {
        let duration = max(0, end - start)
        guard duration >= 0.5 else { return nil }
        let asset = AVURLAsset(url: url)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            return try? Data(contentsOf: url)
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("circle_trim_\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: out) }
        export.outputURL = out
        export.outputFileType = .mp4
        export.metadata = []
        export.metadataItemFilter = .forSharing()
        export.timeRange = CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: 600),
            end: CMTime(seconds: end, preferredTimescale: 600)
        )
        await export.export()
        guard export.status == .completed, let data = try? Data(contentsOf: out) else {
            return try? Data(contentsOf: url)
        }
        return data
    }
}
