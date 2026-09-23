import AppKit
import Foundation
import Vision

// kotoba-ocr: Apple's text recognizer for the Java core (Ocr.external). Stays running; one request per line on stdin,
// "<lang>\t<image path>", answered by one JSON line: {"w","h","lines":[{"x","y","w","h","text","conf"}]} in pixels.
setvbuf(stdout, nil, _IOLBF, 0)
let languages = ["ko": ["ko-KR"], "ja": ["ja-JP"], "zh": ["zh-Hans", "zh-Hant"], "th": ["th-TH"], "ru": ["ru-RU"], "en": ["en-US"]]

func answer(_ object: Any) {
    let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{\"error\":\"encode\"}".utf8)
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data("\n".utf8))
}

while let request = readLine() {
    let parts = request.split(separator: "\t", maxSplits: 1).map(String.init)
    guard parts.count == 2 else { answer(["error": "bad request"]); continue }
    guard let image = NSImage(contentsOfFile: parts[1]),
          let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { answer(["error": "unreadable image"]); continue }
    let w = CGFloat(cg.width), h = CGFloat(cg.height)
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = languages[parts[0]] ?? ["ko-KR"]
    req.usesLanguageCorrection = true
    // Tall webtoon strips: Vision shrinks the whole image, so read it in overlapping bands about as tall as wide × 2.5.
    let band = h > w * 3 ? max(w * 2.5, 64) : h, overlap = h > w * 3 ? w / 4 : 0
    var lines: [[String: Any]] = []
    var y0: CGFloat = 0
    do {
        while true {
            let bh = min(band, h - y0)
            let rect = CGRect(x: 0, y: y0, width: w, height: bh)
            guard let part = cg.cropping(to: rect) else { break }
            try VNImageRequestHandler(cgImage: part, options: [:]).perform([req])
            for o in req.results ?? [] {
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
        answer(["w": w, "h": h, "lines": lines])
    } catch {
        answer(["error": error.localizedDescription])
    }
}
