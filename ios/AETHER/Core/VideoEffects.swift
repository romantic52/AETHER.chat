import Combine
import CoreImage
import CoreImage.CIFilterBuiltins
import CoreVideo
import Foundation
import Metal
import UIKit
import Vision
import WebRTC

// Маски исходящего видео. Накладываются ДО отправки в WebRTC, то есть их видит
// и собеседник, а не только локальное превью.
enum CallMask: String, CaseIterable, Identifiable {
    case none, glasses, crown, ears, blurBg, neon, noir

    var id: String { rawValue }

    var title: String {
        switch self {
        case .none:    return "Без маски"
        case .glasses: return "Очки"
        case .crown:   return "Корона"
        case .ears:    return "Ушки"
        case .blurBg:  return "Размытие"
        case .neon:    return "Неон"
        case .noir:    return "Нуар"
        }
    }

    var chip: String {
        switch self {
        case .none:    return "🚫"
        case .glasses: return "🕶️"
        case .crown:   return "👑"
        case .ears:    return "🐰"
        case .blurBg:  return "🌫️"
        case .neon:    return "✨"
        case .noir:    return "🎞️"
        }
    }

    /// Маски-наклейки, которым нужны точки лица.
    var needsFace: Bool {
        switch self {
        case .glasses, .crown, .ears: return true
        default: return false
        }
    }

    var needsSegmentation: Bool { self == .blurBg }
    var isActive: Bool { self != .none }
}

// Распознаваемые знаки рукой.
enum HandSign: String, Identifiable {
    case peace, thumbsUp, palm, fist, rock

    var id: String { rawValue }

    var emoji: String {
        switch self {
        case .peace:    return "✌️"
        case .thumbsUp: return "👍"
        case .palm:     return "👋"
        case .fist:     return "✊"
        case .rock:     return "🤘"
        }
    }

    var title: String {
        switch self {
        case .peace:    return "Мир"
        case .thumbsUp: return "Класс"
        case .palm:     return "Привет"
        case .fist:     return "Кулак"
        case .rock:     return "Рок"
        }
    }
}

// Кадровый процессор камеры: сидит между RTCCameraVideoCapturer и RTCVideoSource.
// Без активной маски и без вспышки жеста кадр проходит насквозь без единого копирования.
final class VideoEffects: NSObject, ObservableObject, RTCVideoCapturerDelegate {

    // MARK: - Публичное состояние (main)

    @Published var mask: CallMask = .none { didSet { syncSettings() } }
    @Published var gesturesEnabled = false { didSet { syncSettings() } }
    /// Последний распознанный знак — для локальной анимации в UI.
    @Published private(set) var sign: HandSign?

    // MARK: - Внутреннее состояние (под замком, читается с потока камеры)

    private struct FaceGeom {
        let leftEye: CGPoint     // нормализованные координаты выпрямленного кадра
        let rightEye: CGPoint
        let box: CGRect
        let at: CFTimeInterval
    }

    private struct Burst {
        let emoji: String
        let at: CFTimeInterval
        static let duration: CFTimeInterval = 1.6
    }

    private let lock = NSLock()
    private var activeMask: CallMask = .none
    private var wantsGestures = false
    private var face: FaceGeom?
    private var segMask: CIImage?
    private var burst: Burst?

    private weak var source: RTCVideoSource?

    private let analysisQueue = DispatchQueue(label: "io.aether.videoeffects.vision", qos: .userInitiated)
    private var analyzing = false
    private var lastAnalysis: CFTimeInterval = 0
    private var pendingSign: HandSign?
    private var pendingCount = 0
    private var lastSignAt: CFTimeInterval = 0

    private lazy var ciContext: CIContext = {
        if let device = MTLCreateSystemDefaultDevice() {
            return CIContext(mtlDevice: device, options: [.cacheIntermediates: false])
        }
        return CIContext(options: [.cacheIntermediates: false])
    }()

    private var pool: CVPixelBufferPool?
    private var poolWidth = 0
    private var poolHeight = 0

    private var glyphCache: [String: CIImage] = [:]
    private let glyphLock = NSLock()

    // MARK: - Жизненный цикл

    func attach(to source: RTCVideoSource) {
        self.source = source
    }

    func detach() {
        source = nil
        lock.lock()
        face = nil
        segMask = nil
        burst = nil
        lock.unlock()
    }

    /// Сброс между звонками: маска остаётся выбором пользователя, вспышки — нет.
    func resetTransient() {
        lock.lock()
        face = nil
        segMask = nil
        burst = nil
        lock.unlock()
        DispatchQueue.main.async { self.sign = nil }
    }

    private func syncSettings() {
        lock.lock()
        activeMask = mask
        wantsGestures = gesturesEnabled
        if !mask.needsSegmentation { segMask = nil }
        lock.unlock()
    }

    // MARK: - RTCVideoCapturerDelegate

    func capturer(_ capturer: RTCVideoCapturer, didCapture frame: RTCVideoFrame) {
        guard let source else { return }

        lock.lock()
        let mask = activeMask
        let gestures = wantsGestures
        var burstNow = burst
        if let b = burstNow, CACurrentMediaTime() - b.at > Burst.duration {
            burst = nil
            burstNow = nil
        }
        lock.unlock()

        guard mask.isActive || gestures || burstNow != nil,
              let rtcBuffer = frame.buffer as? RTCCVPixelBuffer else {
            source.capturer(capturer, didCapture: frame)
            return
        }

        let orientation = Self.orientation(for: frame.rotation)
        scheduleAnalysis(rtcBuffer.pixelBuffer, orientation: orientation, mask: mask, gestures: gestures)

        // Только распознавание жестов, без наложения — кадр не трогаем.
        guard mask.isActive || burstNow != nil else {
            source.capturer(capturer, didCapture: frame)
            return
        }

        guard let output = render(rtcBuffer.pixelBuffer, mask: mask, burst: burstNow, orientation: orientation) else {
            source.capturer(capturer, didCapture: frame)
            return
        }

        let processed = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: output),
                                      rotation: frame.rotation,
                                      timeStampNs: frame.timeStampNs)
        source.capturer(capturer, didCapture: processed)
    }

    // MARK: - Vision

    private func scheduleAnalysis(_ pixelBuffer: CVPixelBuffer,
                                  orientation: CGImagePropertyOrientation,
                                  mask: CallMask,
                                  gestures: Bool) {
        let needFace = mask.needsFace
        let needSeg = mask.needsSegmentation
        guard needFace || needSeg || gestures else { return }

        let now = CACurrentMediaTime()
        lock.lock()
        let busy = analyzing || now - lastAnalysis < 0.08
        if !busy {
            analyzing = true
            lastAnalysis = now
        }
        lock.unlock()
        guard !busy else { return }

        analysisQueue.async { [weak self] in
            guard let self else { return }
            self.analyze(pixelBuffer, orientation: orientation,
                         needFace: needFace, needSeg: needSeg, needHands: gestures)
            self.lock.lock(); self.analyzing = false; self.lock.unlock()
        }
    }

    private func analyze(_ pixelBuffer: CVPixelBuffer,
                         orientation: CGImagePropertyOrientation,
                         needFace: Bool,
                         needSeg: Bool,
                         needHands: Bool) {
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
        var requests: [VNRequest] = []

        let faceRequest = VNDetectFaceLandmarksRequest()
        if needFace { requests.append(faceRequest) }

        let segRequest = VNGeneratePersonSegmentationRequest()
        segRequest.qualityLevel = .fast
        segRequest.outputPixelFormat = kCVPixelFormatType_OneComponent8
        if needSeg { requests.append(segRequest) }

        let handRequest = VNDetectHumanHandPoseRequest()
        handRequest.maximumHandCount = 1
        if needHands { requests.append(handRequest) }

        guard !requests.isEmpty, (try? handler.perform(requests)) != nil else { return }

        if needFace {
            let geom = Self.faceGeometry(from: faceRequest.results?.first)
            lock.lock(); face = geom; lock.unlock()
        }
        if needSeg, let mask = (segRequest.results?.first)?.pixelBuffer {
            let image = CIImage(cvPixelBuffer: mask)
            lock.lock(); segMask = image; lock.unlock()
        }
        if needHands {
            handleHands(handRequest.results?.first)
        }
    }

    private static func faceGeometry(from observation: VNFaceObservation?) -> FaceGeom? {
        guard let observation, let landmarks = observation.landmarks,
              let left = landmarks.leftEye, let right = landmarks.rightEye else { return nil }
        let box = observation.boundingBox

        func center(_ region: VNFaceLandmarkRegion2D) -> CGPoint {
            let points = region.normalizedPoints
            guard !points.isEmpty else { return CGPoint(x: box.midX, y: box.midY) }
            let sum = points.reduce(CGPoint.zero) { CGPoint(x: $0.x + CGFloat($1.x), y: $0.y + CGFloat($1.y)) }
            let mid = CGPoint(x: sum.x / CGFloat(points.count), y: sum.y / CGFloat(points.count))
            return CGPoint(x: box.minX + mid.x * box.width, y: box.minY + mid.y * box.height)
        }

        return FaceGeom(leftEye: center(left), rightEye: center(right), box: box, at: CACurrentMediaTime())
    }

    private func handleHands(_ observation: VNHumanHandPoseObservation?) {
        guard let observation, let detected = Self.classify(observation) else {
            pendingSign = nil
            pendingCount = 0
            return
        }
        if detected == pendingSign { pendingCount += 1 } else { pendingSign = detected; pendingCount = 1 }
        guard pendingCount >= 2 else { return }

        let now = CACurrentMediaTime()
        guard now - lastSignAt > 2.5 else { return }
        lastSignAt = now
        pendingCount = 0

        lock.lock(); burst = Burst(emoji: detected.emoji, at: now); lock.unlock()
        DispatchQueue.main.async {
            self.sign = detected
            DispatchQueue.main.asyncAfter(deadline: .now() + Burst.duration) {
                if self.sign == detected { self.sign = nil }
            }
        }
    }

    private static func classify(_ observation: VNHumanHandPoseObservation) -> HandSign? {
        guard let points = try? observation.recognizedPoints(.all) else { return nil }

        func point(_ joint: VNHumanHandPoseObservation.JointName) -> CGPoint? {
            guard let p = points[joint], p.confidence > 0.3 else { return nil }
            return p.location
        }
        guard let wrist = point(.wrist) else { return nil }

        func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }
        func extended(_ tip: VNHumanHandPoseObservation.JointName,
                      _ knuckle: VNHumanHandPoseObservation.JointName) -> Bool {
            guard let t = point(tip), let k = point(knuckle) else { return false }
            return distance(t, wrist) > distance(k, wrist) * 1.15
        }

        let thumb = extended(.thumbTip, .thumbIP)
        let index = extended(.indexTip, .indexPIP)
        let middle = extended(.middleTip, .middlePIP)
        let ring = extended(.ringTip, .ringPIP)
        let little = extended(.littleTip, .littlePIP)

        switch (thumb, index, middle, ring, little) {
        case (_, true, true, false, false):     return .peace
        case (true, false, false, false, false): return .thumbsUp
        case (_, true, true, true, true):       return .palm
        case (false, false, false, false, false): return .fist
        case (_, true, false, false, true):     return .rock
        default: return nil
        }
    }

    // MARK: - Отрисовка

    private func render(_ source: CVPixelBuffer,
                        mask: CallMask,
                        burst: Burst?,
                        orientation: CGImagePropertyOrientation) -> CVPixelBuffer? {
        let base = CIImage(cvPixelBuffer: source).oriented(orientation)
        let extent = base.extent
        guard extent.width > 1, extent.height > 1 else { return nil }

        lock.lock()
        let geom = face
        let segmentation = segMask
        lock.unlock()
        let fresh = geom.map { CACurrentMediaTime() - $0.at < 0.5 } ?? false

        var image = base
        switch mask {
        case .none:
            break
        case .noir:
            image = image.applyingFilter("CIPhotoEffectNoir")
                .applyingFilter("CIVignette", parameters: [kCIInputIntensityKey: 1.2, kCIInputRadiusKey: 1.6])
        case .neon:
            image = image
                .applyingFilter("CIColorControls", parameters: [
                    kCIInputSaturationKey: 1.8, kCIInputContrastKey: 1.12, kCIInputBrightnessKey: 0.02,
                ])
                .applyingFilter("CIBloom", parameters: [kCIInputRadiusKey: 12, kCIInputIntensityKey: 0.9])
                .cropped(to: extent)
        case .blurBg:
            if let segmentation {
                let scale = CGAffineTransform(scaleX: extent.width / segmentation.extent.width,
                                              y: extent.height / segmentation.extent.height)
                let fitted = segmentation.transformed(by: scale)
                let blurred = image.clampedToExtent()
                    .applyingGaussianBlur(sigma: extent.width * 0.018)
                    .cropped(to: extent)
                image = image.applyingFilter("CIBlendWithMask", parameters: [
                    kCIInputBackgroundImageKey: blurred,
                    kCIInputMaskImageKey: fitted,
                ])
            }
        case .glasses, .crown, .ears:
            if let geom, fresh {
                image = overlaySticker(mask, on: image, geom: geom, extent: extent)
            }
        }

        if let burst {
            let progress = min(max((CACurrentMediaTime() - burst.at) / Burst.duration, 0), 1)
            let alpha = progress > 0.7 ? (1 - (progress - 0.7) / 0.3) : min(progress / 0.12, 1)
            let pop = 1 + 0.18 * sin(min(progress / 0.25, 1) * .pi)
            if let glyph = glyph(burst.emoji) {
                image = place(glyph,
                              on: image,
                              center: CGPoint(x: extent.midX, y: extent.minY + extent.height * 0.24),
                              width: extent.width * 0.26 * pop,
                              angle: 0,
                              alpha: CGFloat(alpha))
            }
        }

        let restored = image.oriented(Self.inverse(orientation))
        let bounds = restored.extent
        guard let output = makeBuffer(width: Int(bounds.width.rounded()), height: Int(bounds.height.rounded())) else { return nil }
        ciContext.render(restored, to: output, bounds: bounds, colorSpace: CGColorSpaceCreateDeviceRGB())
        return output
    }

    private func overlaySticker(_ mask: CallMask, on image: CIImage, geom: FaceGeom, extent: CGRect) -> CIImage {
        func denorm(_ p: CGPoint) -> CGPoint {
            CGPoint(x: extent.minX + p.x * extent.width, y: extent.minY + p.y * extent.height)
        }
        let left = denorm(geom.leftEye)
        let right = denorm(geom.rightEye)
        let eyeSpan = max(hypot(right.x - left.x, right.y - left.y), extent.width * 0.04)
        let angle = atan2(right.y - left.y, right.x - left.x)
        let faceWidth = geom.box.width * extent.width
        let headTop = extent.minY + geom.box.maxY * extent.height
        let headCenterX = extent.minX + geom.box.midX * extent.width

        let emoji: String
        let center: CGPoint
        let width: CGFloat
        switch mask {
        case .glasses:
            emoji = "🕶️"
            center = CGPoint(x: (left.x + right.x) / 2, y: (left.y + right.y) / 2)
            width = eyeSpan * 2.5
        case .crown:
            emoji = "👑"
            center = CGPoint(x: headCenterX, y: headTop + faceWidth * 0.24)
            width = faceWidth * 0.95
        default:
            emoji = "🐰"
            center = CGPoint(x: headCenterX, y: headTop + faceWidth * 0.34)
            width = faceWidth * 1.05
        }
        guard let glyph = glyph(emoji) else { return image }
        return place(glyph, on: image, center: center, width: width, angle: angle, alpha: 1)
    }

    private func place(_ glyph: CIImage,
                       on image: CIImage,
                       center: CGPoint,
                       width: CGFloat,
                       angle: CGFloat,
                       alpha: CGFloat) -> CIImage {
        guard glyph.extent.width > 0, alpha > 0.01 else { return image }
        let scale = width / glyph.extent.width
        var transform = CGAffineTransform(translationX: center.x, y: center.y)
        transform = transform.rotated(by: angle)
        transform = transform.scaledBy(x: scale, y: scale)
        transform = transform.translatedBy(x: -glyph.extent.width / 2, y: -glyph.extent.height / 2)

        var placed = glyph.transformed(by: transform)
        if alpha < 0.99 {
            placed = placed.applyingFilter("CIColorMatrix", parameters: [
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: alpha),
            ])
        }
        return placed.composited(over: image)
    }

    /// Эмодзи растеризуется один раз и кэшируется — по кадру рисовать текст слишком дорого.
    private func glyph(_ emoji: String) -> CIImage? {
        glyphLock.lock()
        if let cached = glyphCache[emoji] { glyphLock.unlock(); return cached }
        glyphLock.unlock()

        let side: CGFloat = 256
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = false
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: side * 0.8)]
            let text = emoji as NSString
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (side - size.width) / 2, y: (side - size.height) / 2), withAttributes: attributes)
        }
        guard let cgImage = rendered.cgImage else { return nil }
        let image = CIImage(cgImage: cgImage)
        glyphLock.lock(); glyphCache[emoji] = image; glyphLock.unlock()
        return image
    }

    // MARK: - Пул буферов

    private func makeBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        if pool == nil || poolWidth != width || poolHeight != height {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
                kCVPixelBufferMetalCompatibilityKey as String: true,
            ]
            var created: CVPixelBufferPool?
            guard CVPixelBufferPoolCreate(kCFAllocatorDefault,
                                          [kCVPixelBufferPoolMinimumBufferCountKey as String: 3] as CFDictionary,
                                          attributes as CFDictionary,
                                          &created) == kCVReturnSuccess else { return nil }
            pool = created
            poolWidth = width
            poolHeight = height
        }
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer) == kCVReturnSuccess else { return nil }
        return buffer
    }

    // MARK: - Ориентация

    private static func orientation(for rotation: RTCVideoRotation) -> CGImagePropertyOrientation {
        switch rotation {
        case ._0:   return .up
        case ._90:  return .right
        case ._180: return .down
        case ._270: return .left
        @unknown default: return .up
        }
    }

    private static func inverse(_ orientation: CGImagePropertyOrientation) -> CGImagePropertyOrientation {
        switch orientation {
        case .right: return .left
        case .left:  return .right
        default:     return orientation
        }
    }
}
