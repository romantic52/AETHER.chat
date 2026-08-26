import UIKit
import ImageIO
import UniformTypeIdentifiers

// Дисковый и memory-кэш расшифрованного медиа. Это отдельный actor: чтение файлов,
// запись и декодирование изображений никогда не блокируют главный поток SwiftUI.
actor MediaStore {
    static let shared = MediaStore()

    private let memCache = NSCache<NSString, UIImage>()
    private let fileCache: URL
    private var inflight: [String: Task<Data?, Never>] = [:]
    private var materializedURLs: Set<URL> = []
    private var core: CoreClient?
    private var writesSinceTrim = 0

    init() {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileCache = dir
        memCache.countLimit = 80
        memCache.totalCostLimit = 96 * 1024 * 1024
    }

    func bind(core: CoreClient) {
        self.core = core
        trimDiskCache(maxBytes: 512 * 1024 * 1024)
    }

    private func diskURL(_ fileId: String) -> URL {
        let safe = fileId.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return fileCache.appendingPathComponent(safe)
    }

    /// Сырые расшифрованные байты. Одновременные запросы одного файла объединяются.
    func data(fileId: String, symKey: String, nonce: String) async -> Data? {
        let disk = diskURL(fileId)
        if let data = try? Data(contentsOf: disk, options: .mappedIfSafe) { return data }
        if let task = inflight[fileId] { return await task.value }

        let task = Task<Data?, Never> { [weak self] in
            guard let self, let core = await self.core else { return nil }
            guard let data = try? await core.downloadMedia(fileId: fileId, symKey: symKey, nonce: nonce) else {
                return nil
            }
            await self.persist(data, fileId: fileId)
            return data
        }
        inflight[fileId] = task
        let result = await task.value
        inflight[fileId] = nil
        return result
    }

    /// Единая точка чтения современного медиа и старых web-сообщений, где файл
    /// лежал прямо в зашифрованном payload как data URL.
    func data(payload: Wire.Payload) async -> Data? {
        if let inline = payload.inlineData { return inline }
        guard let fileId = payload.fileId else { return nil }
        return await data(fileId: fileId, symKey: payload.symKey ?? "", nonce: payload.nonce ?? "")
    }

    func seed(fileId: String, data: Data) {
        persist(data, fileId: fileId)
    }

    // MARK: - Управление кешем (Настройки → Данные и память)

    /// Суммарный размер дискового кеша медиа в байтах.
    func cacheSizeBytes() -> Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: fileCache, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(Int64(0)) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    /// Полная очистка кеша медиа (диск + память). Сообщения не трогаем —
    /// медиа при следующем открытии просто скачается заново.
    func clearCache() {
        memCache.removeAllObjects()
        let files = (try? FileManager.default.contentsOfDirectory(
            at: fileCache, includingPropertiesForKeys: nil)) ?? []
        for url in files { try? FileManager.default.removeItem(at: url) }
        for url in materializedURLs { try? FileManager.default.removeItem(at: url) }
        materializedURLs.removeAll()
    }

    /// Стереть один файл из кэша — и с диска, и из памяти.
    ///
    /// Нужно для исчезающих сообщений: если оставить файл, «сообщение
    /// исчезло» будет неправдой — картинка продолжит лежать на устройстве.
    func remove(fileId: String) {
        memCache.removeObject(forKey: fileId as NSString)
        try? FileManager.default.removeItem(at: diskURL(fileId))
        // Файлы, уже выданные системным просмотрщикам, тоже подчищаем.
        // materializedURLs — это Set, поэтому сначала выбираем, потом вычитаем.
        let stale = materializedURLs.filter { $0.lastPathComponent.contains(fileId) }
        for url in stale { try? FileManager.default.removeItem(at: url) }
        materializedURLs.subtract(stale)
    }

    private func persist(_ data: Data, fileId: String) {
        try? data.write(to: diskURL(fileId), options: .atomic)
        writesSinceTrim += 1
        if writesSinceTrim >= 20 {
            writesSinceTrim = 0
            trimDiskCache(maxBytes: 512 * 1024 * 1024)
        }
    }

    func image(fileId: String, symKey: String, nonce: String, maxPixel: CGFloat) async -> UIImage? {
        let key = "\(fileId)@\(Int(maxPixel))" as NSString
        if let image = memCache.object(forKey: key) { return image }
        guard let data = await data(fileId: fileId, symKey: symKey, nonce: nonce) else { return nil }
        let image = Self.downsample(data: data, maxPixel: maxPixel) ?? UIImage(data: data)
        if let image {
            let cost = Int(image.size.width * image.scale * image.size.height * image.scale * 4)
            memCache.setObject(image, forKey: key, cost: cost)
        }
        return image
    }

    func image(payload: Wire.Payload, maxPixel: CGFloat) async -> UIImage? {
        if let inline = payload.inlineData {
            return Self.downsample(data: inline, maxPixel: maxPixel) ?? UIImage(data: inline)
        }
        guard let fileId = payload.fileId else { return nil }
        return await image(fileId: fileId, symKey: payload.symKey ?? "",
                           nonce: payload.nonce ?? "", maxPixel: maxPixel)
    }

    nonisolated static func downsample(data: Data, maxPixel: CGFloat) -> UIImage? {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, opts) else { return nil }
        let thumbOpts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            // maxPixel уже передаётся в физических пикселях. Повторное умножение
            // на scale раньше раздувало полноэкранное фото до 7K.
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int(maxPixel)),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOpts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    func materialize(fileId: String, fileName: String, symKey: String, nonce: String) async -> URL? {
        guard let data = await data(fileId: fileId, symKey: symKey, nonce: nonce) else { return nil }
        let cleanName = URL(fileURLWithPath: fileName).lastPathComponent
        let safeName = cleanName.isEmpty ? fileId.replacingOccurrences(of: "/", with: "_") : cleanName
        let prefix = fileId.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)_\(safeName)")
        do {
            try data.write(to: url, options: .atomic)
            materializedURLs.insert(url)
            return url
        } catch {
            return nil
        }
    }

    func materialize(payload: Wire.Payload, fallbackExtension: String) async -> URL? {
        guard let data = await data(payload: payload) else { return nil }
        let inferred = payload.mimeType.flatMap { UTType(mimeType: $0)?.preferredFilenameExtension }
            ?? fallbackExtension
        var name = payload.fileName ?? "aether_\(payload.fileId ?? UUID().uuidString).\(inferred)"
        if URL(fileURLWithPath: name).pathExtension.isEmpty { name += ".\(inferred)" }
        let safeName = URL(fileURLWithPath: name).lastPathComponent
        let rawPrefix = payload.fileId ?? UUID().uuidString
        let prefix = rawPrefix.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)_\(safeName)")
        do {
            try data.write(to: url, options: .atomic)
            materializedURLs.insert(url)
            return url
        } catch {
            return nil
        }
    }

    private func trimDiskCache(maxBytes: Int) {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: fileCache, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]
        ) else { return }

        var files: [(url: URL, size: Int, date: Date)] = []
        var total = 0
        for url in urls {
            guard let values = try? url.resourceValues(forKeys: keys), values.isRegularFile == true else { continue }
            let size = values.fileSize ?? 0
            total += size
            files.append((url, size, values.contentModificationDate ?? .distantPast))
        }
        guard total > maxBytes else { return }
        for file in files.sorted(by: { $0.date < $1.date }) where total > maxBytes {
            try? FileManager.default.removeItem(at: file.url)
            total -= file.size
        }
    }
}
