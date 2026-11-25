import UIKit
import Vision

/// Apple's text recognizer, the first reading of comic and screen text (the same approach as the Mac's helper).
enum VisionOCR {
    static let languages: [String: [String]] = [
        "ko": ["ko-KR"], "ja": ["ja-JP"], "zh": ["zh-Hans", "zh-Hant"], "th": ["th-TH"], "ru": ["ru-RU"], "en": ["en-US"],
    ]

    /// {w,h,lines:[{x,y,w,h,text,conf}]} in pixels (origin top-left), or an error message.
    static func recognize(_ data: Data, lang: String) -> (json: String?, error: String?) {
        guard let image = UIImage(data: data), let cg = image.cgImage else { return (nil, "unreadable image") }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = languages[lang] ?? ["ko-KR"]
        request.usesLanguageCorrection = true
        // Tall webtoon strips: Vision shrinks the whole image, so read it in overlapping bands about as tall as wide × 2.5.
        let band = h > w * 3 ? max(w * 2.5, 64) : h
        let overlap = h > w * 3 ? w / 4 : 0
        var lines: [[String: Any]] = []
        var y0: CGFloat = 0
        do {
            while true {
                let bh = min(band, h - y0)
                guard let part = cg.cropping(to: CGRect(x: 0, y: y0, width: w, height: bh)) else { break }
                try VNImageRequestHandler(cgImage: part, options: [:]).perform([request])
                for o in request.results ?? [] {
                    guard let top = o.topCandidates(1).first else { continue }
                    let b = o.boundingBox  // normalized, origin bottom-left
                    let x = b.minX * w, y = (1 - b.maxY) * bh + y0, lw = b.width * w, lh = b.height * bh
                    let cy = y - y0 + lh / 2
                    // Keep a line only in the band that holds its centre away from the seam.
                    if y0 > 0 && cy < overlap / 2 { continue }
                    if y0 + bh < h && cy > bh - overlap / 2 { continue }
                    lines.append(["x": x, "y": y, "w": lw, "h": lh, "text": top.string, "conf": top.confidence])
                }
                if y0 + bh >= h { break }
                y0 += band - overlap
            }
            let json = try JSONSerialization.data(withJSONObject: ["w": w, "h": h, "lines": lines])
            return (String(data: json, encoding: .utf8), nil)
        } catch {
            return (nil, error.localizedDescription)
        }
    }
}
