import UIKit
import WebKit
import UniformTypeIdentifiers

/// Kotoba's interface in a web view, served from the app: pages and files come from the bundle and the translated core
/// through a `kotoba://` scheme, requests to the core arrive as script messages, and pickers are the system's.
/// Debug tooling (layout probe, screen measurements, request log) runs only while a file named debug.on is in Documents.
var debugOn: Bool {
    FileManager.default.fileExists(atPath: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("debug.on").path)
}

/// A log the Mac can copy off the iPad (Documents/kotoba.log; the newest ~200 KB).
func appLog(_ line: String) {
    let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("kotoba.log")
    // Always keep problems; everything else only while debugging.
    if !debugOn && !(line.hasPrefix("[core]") || line.hasPrefix("[scheme] 4") || line.hasPrefix("[web error]") || line.hasPrefix("[nav]") || line.hasPrefix("[pick]") || line.hasPrefix("[sync]")) { return }
    let text = "\(Date()) \(line)\n"
    if let h = try? FileHandle(forWritingTo: url) {
        h.seekToEndOfFile(); h.write(Data(text.utf8))
        if h.offsetInFile > 400_000 { try? h.close(); try? FileManager.default.removeItem(at: url) } else { try? h.close() }
    } else { try? Data(text.utf8).write(to: url) }
}

/// The app's memory footprint in MB (what the system counts against its limit).
func footprintMB() -> Int {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
    let r = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) } }
    return r == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
}

final class WebViewController: UIViewController, WKScriptMessageHandler, WKURLSchemeHandler, UIDocumentPickerDelegate, WKNavigationDelegate, WKUIDelegate {
    private var web: WKWebView!
    private var core: KotobaCore!
    private let work = DispatchQueue(label: "kotoba.core", qos: .userInitiated, attributes: .concurrent)
    private var stopped = Set<ObjectIdentifier>()
    private let lock = NSLock()
    private var pickPurpose = ""
    private var pickFolder = false
    private var accessed: [URL] = []

    private let bundleRoot = Bundle.main.resourceURL!
    private var assetsDir: URL { bundleRoot.appendingPathComponent("assets") }
    private var webDir: URL { bundleRoot.appendingPathComponent("web") }
    private var dataDir: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Kotoba")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // The strips around the page start in the last theme's colour, so a dark theme doesn't flash light at launch.
        let last = UserDefaults.standard.string(forKey: "barColor") ?? ""
        view.backgroundColor = Self.color(hex: last) ?? UIColor(red: 0.98, green: 0.97, blue: 0.95, alpha: 1)
        // Dictionary imports take minutes and stop when the iPad sleeps, so the screen stays on while Kotoba is in front.
        UIApplication.shared.isIdleTimerDisabled = true
        for (name, label) in [(UIApplication.didBecomeActiveNotification, "active"), (UIApplication.willResignActiveNotification, "resign active"),
                              (UIApplication.didEnterBackgroundNotification, "background"), (UIApplication.willEnterForegroundNotification, "foreground"),
                              (UIApplication.protectedDataWillBecomeUnavailableNotification, "device locked")] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in appLog("[life] \(label)") }
        }
        reopenFolders()
        core = KotobaCore(data: dataDir.path, assets: assetsDir.path, device: UIDevice.current.name) { [weak self] json in
            if json.contains("\"type\":\"import") && !json.contains("\"stage\"") { appLog("[event] \(json.prefix(200))") }
            else if json.contains("\"type\":\"import\"") { appLog("[import] \(json.prefix(160)) mem=\(footprintMB())MB") }
            self?.script("window.__event&&window.__event(\(Self.quote(json)))")
        }
        core.setRecognizer { data, lang, error in
            let r = VisionOCR.recognize(data, lang: lang)
            error.pointee = r.error as NSString?
            return r.json
        }
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(self, forURLScheme: "kotoba")
        config.userContentController.add(self, name: "kotoba")
        // What the page says (console output, errors) goes to the app's log, so a problem on the iPad can be read from the Mac.
        let forward = """
        (function(){
          const send=(level,args)=>{try{window.webkit.messageHandlers.kotoba.postMessage({type:'log',level,text:args.map(a=>{try{return typeof a==='string'?a:(a&&a.stack)||JSON.stringify(a);}catch(e){return String(a);}}).join(' ')});}catch(e){}};
          for(const l of ['log','warn','error']){const o=console[l];console[l]=function(){send(l,[...arguments]);o.apply(console,arguments);};}
          window.addEventListener('error',e=>send('error',['uncaught',e.message,(e.filename||'')+':'+e.lineno]));
          window.addEventListener('unhandledrejection',e=>send('error',['unhandled',e.reason&&e.reason.message||e.reason]));
        })();
        """
        let docsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        config.userContentController.addUserScript(WKUserScript(source: "window.KOTOBA_DOCS=\(Self.quote(docsPath));", injectionTime: .atDocumentStart, forMainFrameOnly: true))
        appLog("[app] Documents = \(docsPath)")
        config.userContentController.addUserScript(WKUserScript(source: forward, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        web = WKWebView(frame: view.bounds, configuration: config)
        // Clear until the page paints, so the theme colour (not white) shows while it loads.
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        web.navigationDelegate = self
        web.uiDelegate = self
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        if #available(iOS 16.4, *) { web.isInspectable = true }
        view.addSubview(web)
        // The page sits inside the safe area; the strips outside it are the controller's own view, coloured by setBars.
        web.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            web.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
        ])
        if UserDefaults.standard.object(forKey: "barLight") != nil {
            overrideUserInterfaceStyle = UserDefaults.standard.bool(forKey: "barLight") ? .light : .dark
        }
        web.load(URLRequest(url: URL(string: "kotoba://app/")!))
        // While a book is open, its layout is written to the log every few seconds (a blank page can then be explained from the Mac).
        let probe = """
        (function(){const f=document.querySelector('.reader-page iframe');if(!f)return '';const d=f.contentDocument;if(!d||!d.body)return 'no doc';
          const b=d.body,de=d.documentElement,cs=getComputedStyle(b),ds=getComputedStyle(de),r=b.getBoundingClientRect();
          const tn=document.createTreeWalker(b,NodeFilter.SHOW_TEXT);let first=null,n;while((n=tn.nextNode())){if(n.textContent.trim()){first=n;break;}}
          let fr=null;if(first){const rg=d.createRange();rg.selectNodeContents(first);const q=rg.getBoundingClientRect();fr=[q.left,q.top,q.width,q.height].map(Math.round);}
          const sc=d.scrollingElement||de;
          return JSON.stringify({frame:[f.clientWidth,f.clientHeight],src:f.src.slice(-40),textLen:(b.innerText||'').length,wmHtml:ds.writingMode,wmBody:cs.writingMode,
            sw:sc.scrollWidth,sh:sc.scrollHeight,cw:sc.clientWidth,ch:sc.clientHeight,sl:Math.round(sc.scrollLeft),st:Math.round(sc.scrollTop),
            bodyRect:[r.left,r.top,r.width,r.height].map(Math.round),firstTextRect:fr,vis:cs.visibility,op:cs.opacity,color:cs.color,bg:cs.backgroundColor,
            htmlStyle:(de.getAttribute('style')||'').slice(0,200),styleTags:d.querySelectorAll('style').length,imgs:d.images.length,iframeStyle:(f.getAttribute('style')||'').slice(0,150),
            frameRect:(function(){const q=f.getBoundingClientRect();return [q.left,q.top,q.width,q.height].map(Math.round);})(),
            maskL:(document.querySelector('.vpage-mask')||{style:{cssText:'none'}}).style.cssText,maskR:(document.querySelector('.vpage-mask-r')||{style:{cssText:'none'}}).style.cssText,
            top:(function(){const q=f.getBoundingClientRect();return document.elementsFromPoint(q.right-60,q.top+100).slice(0,4).map(e=>e.tagName+'.'+(e.className||'')).join(' > ');})(),
            paged:!!document.querySelector('.reader-page'),pageClass:(document.querySelector('.reader-page')||{className:''}).className});})()
        """
        // A debug aid: JavaScript left in Documents/debug.js runs once in the page and its result is logged; a snapshot then
        // measures how much of the page is ink (dark pixels), so a rendering problem can be tested without looking at the iPad.
        Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            guard let self, debugOn else { return }
            let doc = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let file = doc.appendingPathComponent("debug.js")
            if let code = try? String(contentsOf: file, encoding: .utf8) {
                try? FileManager.default.removeItem(at: file)
                self.web.evaluateJavaScript(code) { r, e in
                    appLog("[debug] result: \(r.map { "\($0)" } ?? "nil") error: \(e?.localizedDescription ?? "-")")
                    // A result starting "SNAP" also saves what the page looks like (Documents/snap.png) a moment later.
                    if let s = r as? String, s.hasPrefix("SNAP") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            self.web.takeSnapshot(with: nil) { image, _ in
                                if let data = image?.pngData() { try? data.write(to: doc.appendingPathComponent("snap.png")); appLog("[debug] snapshot saved") }
                            }
                        }
                    }
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.measureInk(label: "after debug.js") }
            }
        }
        Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { _ in if debugOn { appLog("[mem] \(footprintMB()) MB") } }
        Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in
            guard debugOn else { return }
            self?.web.evaluateJavaScript(probe) { result, error in
                if let t = result as? String, !t.isEmpty { appLog("[probe] \(t)"); self?.measureInk(label: "probe") } else if let error { appLog("[probe] error \(error.localizedDescription)") }
            }
        }
        // Syncs with the chosen folder every 30 seconds while the app is open (only reads or writes when something changed).
        Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self else { return }
            self.fetchCloudFiles()
            let dirs = self.accessed
            self.work.async {
                Self.waitForCloudFiles(in: dirs)
                if let problem = self.core.syncQuietly() { appLog("[sync] \(String(problem.prefix(4000)))") }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.fetchCloudFiles() }
    }

    /// The fraction of dark pixels in the middle of the screen (text is dark on the reader's paper), logged.
    func measureInk(label: String) {
        web.takeSnapshot(with: nil) { image, _ in
            guard let cg = image?.cgImage else { appLog("[ink] \(label): no snapshot"); return }
            let w = 160, h = 120
            var px = [UInt8](repeating: 255, count: w * h * 4)
            guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            var dark = 0, total = 0
            for y in (h / 6)..<(h * 5 / 6) { for x in (w / 8)..<w {   // below the toolbar, right of the sidebar
                let i = (y * w + x) * 4
                if Int(px[i]) + Int(px[i + 1]) + Int(px[i + 2]) < 3 * 232 { dark += 1 }
                total += 1
            } }
            appLog("[ink] \(label): \(String(format: "%.4f", Double(dark) / Double(max(1, total)))) dark")
        }
    }

    override var prefersStatusBarHidden: Bool { false }
    override var preferredStatusBarStyle: UIStatusBarStyle { .default }

    // MARK: pages and files

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        let range = task.request.value(forHTTPHeaderField: "Range")
        work.async { [weak self] in
            guard let self else { return }
            let (status, mime, data, csp) = self.resource(for: url.path)
            appLog("[req] \(status) \(url.path.prefix(120)) \(data.count)b")
            if status != 200 { appLog("[scheme] \(status) \(url.path)") }
            self.finish(task, url: url, status: status, mime: mime, data: data, csp: csp, range: range)
        }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        lock.lock(); stopped.insert(ObjectIdentifier(task)); lock.unlock()
    }

    private static let entryCSP = "default-src 'self' data:; script-src 'none'; style-src 'self' 'unsafe-inline' data:; img-src 'self' data:; media-src 'self' data:; font-src 'self' data:; object-src 'none'; base-uri 'none'"

    private func resource(for path: String) -> (Int, String, Data, String?) {
        func missing() -> (Int, String, Data, String?) { (404, "text/plain", Data(), nil) }
        let decoded = path.removingPercentEncoding ?? path
        for (prefix, kind) in [("/d/", "d"), ("/comic/", "comic"), ("/book/", "book")] where decoded.hasPrefix(prefix) {
            guard let f = core.fileKind(kind, path: String(decoded.dropFirst(prefix.count))),
                  let mime = f[0] as? String, let data = f[1] as? Data else { return missing() }
            return (200, mime, data, f[2] is NSNull ? nil : Self.entryCSP)
        }
        let name = decoded == "/" ? "index.html" : String(decoded.dropFirst())
        guard name.range(of: "^[a-zA-Z0-9_.-]+$", options: .regularExpression) != nil else { return missing() }
        var file = webDir.appendingPathComponent(name)
        if !FileManager.default.fileExists(atPath: file.path) { file = assetsDir.appendingPathComponent(name) }
        guard var data = try? Data(contentsOf: file) else { return missing() }
        if name == "index.html", var html = String(data: data, encoding: .utf8) {
            // The bridge loads before the app's own scripts, as on the Mac.
            let inject = "<link rel=\"stylesheet\" href=\"/desktop.css\"><link rel=\"stylesheet\" href=\"/ipad.css\"><script src=\"/hover-modifier.js\"></script><script src=\"/ipad.js\"></script>"
            if let at = html.range(of: "<script") { html.insert(contentsOf: inject, at: at.lowerBound) } else { html += inject }
            html = html.replacingOccurrences(of: "</body>", with: "<script src=\"/desktop-after.js\"></script></body>")
            data = Data(html.utf8)
        }
        return (200, Self.mime(for: name), data, nil)
    }

    private static func mime(for name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "js": return "text/javascript"
        case "css": return "text/css"
        case "svg": return "image/svg+xml"
        case "woff2": return "font/woff2"
        case "png": return "image/png"
        case "txt": return "text/plain"
        default: return "text/html"
        }
    }

    private func finish(_ task: WKURLSchemeTask, url: URL, status: Int, mime: String, data: Data, csp: String?, range: String?) {
        var headers: [String: String] = ["Content-Type": mime + (mime.hasPrefix("text/") || mime.contains("javascript") ? "; charset=utf-8" : ""),
                                         "Cache-Control": "no-store", "Accept-Ranges": "bytes", "X-Content-Type-Options": "nosniff"]
        if let csp { headers["Content-Security-Policy"] = csp }
        var body = data, code = status
        // Audio and video ask for byte ranges.
        if status == 200, let range, range.hasPrefix("bytes="), !data.isEmpty {
            let parts = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
            if parts.count == 2 {
                let len = data.count
                let start = parts[0].isEmpty ? max(0, len - (Int(parts[1]) ?? 0)) : (Int(parts[0]) ?? 0)
                let end = parts[0].isEmpty || parts[1].isEmpty ? len - 1 : min(len - 1, Int(parts[1]) ?? len - 1)
                if start <= end && start < len {
                    body = data.subdata(in: start..<(end + 1)); code = 206
                    headers["Content-Range"] = "bytes \(start)-\(end)/\(len)"
                }
            }
        }
        headers["Content-Length"] = String(body.count)
        guard let response = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        DispatchQueue.main.async {
            self.lock.lock(); let gone = self.stopped.remove(ObjectIdentifier(task)) != nil; self.lock.unlock()
            if gone { return }
            task.didReceive(response)
            task.didReceive(body)
            task.didFinish()
        }
    }

    // MARK: requests from the interface

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let m = message.body as? [String: Any], let type = m["type"] as? String else { return }
        switch type {
        case "call":
            guard let id = m["id"] as? Int, let route = m["route"] as? String else { return }
            let body = m["body"] as? String ?? "{}"
            let isSync = route.hasPrefix("sync.")
            if isSync { fetchCloudFiles() }
            let dirs = accessed
            work.async { [weak self] in
                guard let self else { return }
                if isSync { Self.waitForCloudFiles(in: dirs) }
                let reply = self.core.call(route, body: body)
                if reply.hasPrefix("{\"error\"") { appLog("[core] \(route) -> \(String(reply.prefix(4000)))") }
                self.script("window.__nativeReply(\(id),\(Self.quote(reply)))")
            }
        case "bars":
            // The theme's (or the open book's) background for the status bar and home indicator strips; light or dark
            // status bar text to match.
            let hex = m["color"] as? String ?? "", light = m["light"] as? Bool ?? true
            guard let color = Self.color(hex: hex) else { return }
            view.backgroundColor = color
            view.window?.backgroundColor = color
            view.window?.overrideUserInterfaceStyle = light ? .light : .dark
            overrideUserInterfaceStyle = light ? .light : .dark
            UserDefaults.standard.set(hex, forKey: "barColor")
            UserDefaults.standard.set(light, forKey: "barLight")
        case "log":
            appLog("[web \(m["level"] as? String ?? "")] \(m["text"] as? String ?? "")")
        case "debugRemove":
            // Debug aid: delete a file or folder under Documents (e.g. a partial copy of dictionaries).
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
            if let path = m["path"] as? String, path.hasPrefix(docs + "/"), !path.contains("..") {
                try? FileManager.default.removeItem(atPath: path); appLog("[debug] removed \(path)")
            }
        case "pickFolder":
            pick(purpose: "dictionaries", extensions: [], multiple: false, folder: true)
        case "pickFile":
            pick(purpose: m["purpose"] as? String ?? "", extensions: m["extensions"] as? [String] ?? [],
                 multiple: m["multiple"] as? Bool ?? false, folder: m["folder"] as? Bool ?? false)
        case "open":
            if let s = m["url"] as? String, let url = URL(string: s), ["http", "https"].contains(url.scheme) { UIApplication.shared.open(url) }
        case "copy":
            UIPasteboard.general.string = m["text"] as? String ?? ""
        case "share":
            present(UIActivityViewController(activityItems: [m["text"] as? String ?? ""], applicationActivities: nil), animated: true)
        case "export":
            export(name: m["name"] as? String ?? "export.txt", text: m["text"] as? String ?? "")
        default:
            break
        }
    }

    private func export(name: String, text: String) {
        let safe = name.replacingOccurrences(of: "/", with: "_")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(safe)
        do { try Data(text.utf8).write(to: url) } catch { return }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        present(sheet, animated: true)
    }

    func script(_ js: String) {
        DispatchQueue.main.async { self.web?.evaluateJavaScript(js, completionHandler: nil) }
    }

    /// `#rrggbb` (or `#rgb`) as a colour.
    static func color(hex: String) -> UIColor? {
        var s = hex.trimmingCharacters(in: .whitespaces)
        guard s.hasPrefix("#") else { return nil }
        s.removeFirst()
        if s.count == 3 { s = s.map { "\($0)\($0)" }.joined() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        return UIColor(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255, blue: CGFloat(v & 0xff) / 255, alpha: 1)
    }

    static func quote(_ s: String) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: [s], options: []), let t = String(data: d, encoding: .utf8) else { return "\"\"" }
        return String(t.dropFirst().dropLast())
    }

    // MARK: pickers

    private func pick(purpose: String, extensions: [String], multiple: Bool, folder: Bool) {
        pickPurpose = purpose; pickFolder = folder
        // Any file can be chosen: the system doesn't know every extension (.cbz, .tachibk), and the interface checks the rest.
        let types: [UTType] = folder ? [.folder] : [.item]
        // Files are copied into the app; a folder is opened where it is (dictionaries and comics can be large) and remembered.
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: !folder)
        picker.allowsMultipleSelection = multiple
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let purpose = pickPurpose
        appLog("[pick] \(purpose) folder=\(pickFolder) \(urls.map { $0.path })")
        if pickFolder, let url = urls.first {
            guard url.startAccessingSecurityScopedResource() else { appLog("[pick] no access to \(url.path)"); script("window.__event(JSON.stringify({type:'toast',data:'Kotoba can’t open that folder.'}))"); return }
            accessed.append(url)
            remember(url)
            if purpose == "dictionaries" {
                script("window.__event(JSON.stringify({type:'folder',data:\(Self.quote(url.path))}))")
            } else {
                script("window.desktopPicked(\(Self.quote(purpose)),\(Self.quote(url.path)))")
            }
            return
        }
        var paths = urls.map { $0.path }
        // Comic archives are read where they are kept, so they move out of the temporary inbox.
        if purpose == "comicFiles" {
            let dir = dataDir.appendingPathComponent("Comics")
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            paths = urls.compactMap { u in
                var to = dir.appendingPathComponent(u.lastPathComponent), n = 2
                while FileManager.default.fileExists(atPath: to.path) { to = dir.appendingPathComponent("\(n) \(u.lastPathComponent)"); n += 1 }
                return (try? FileManager.default.copyItem(at: u, to: to)) != nil ? to.path : nil
            }
        }
        guard !paths.isEmpty else { return }
        let arg = urls.count > 1 || purpose == "books" || purpose == "comicFiles" || purpose == "lyrics"
            ? String(data: try! JSONSerialization.data(withJSONObject: paths), encoding: .utf8)! : Self.quote(paths[0])
        script("window.desktopPicked(\(Self.quote(purpose)),\(arg))")
    }

    /// iCloud Drive keeps other devices' files as placeholders (`.name.icloud`) until something asks for them, and a
    /// downloaded copy can fall behind; the core reads plain files, so the sync files in the opened folders are asked for
    /// here (the next pass then sees them).
    /// Waits (up to 10 s, off the main thread) until iCloud has the newest copy of every sync file here, so the core
    /// never reads one that is still arriving. Files that don't make it are logged and synced next time.
    static func waitForCloudFiles(in dirs: [URL]) {
        let deadline = Date().addingTimeInterval(10)
        var pending: [String] = []
        repeat {
            pending = []
            for dir in dirs {
                guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { continue }
                for n in names {
                    var real = n
                    if n.hasPrefix(".") && n.hasSuffix(".icloud") { real = String(n.dropFirst().dropLast(7)) }
                    guard real.hasPrefix("kotoba-") && real.hasSuffix(".json") else { continue }
                    let url = dir.appendingPathComponent(real)
                    let values = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey, .ubiquitousItemIsDownloadingKey])
                    // Not an iCloud file at all (nil status): nothing to wait for.
                    if let status = values?.ubiquitousItemDownloadingStatus, status != .current { pending.append(real) }
                    else if n != real { pending.append(real) }
                }
            }
            if pending.isEmpty { return }
            Thread.sleep(forTimeInterval: 0.3)
        } while Date() < deadline
        appLog("[sync] still downloading from iCloud: \(pending.joined(separator: ", "))")
    }

    private func fetchCloudFiles() {
        for dir in accessed {
            guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { continue }
            for n in names {
                var real = n
                if n.hasPrefix(".") && n.hasSuffix(".icloud") { real = String(n.dropFirst().dropLast(7)) }
                guard real.hasPrefix("kotoba-") && real.hasSuffix(".json") else { continue }
                do { try FileManager.default.startDownloadingUbiquitousItem(at: dir.appendingPathComponent(real)) }
                catch { if n != real { appLog("[sync] can't fetch \(real): \(error.localizedDescription)") } }
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { appLog("[pick] \(pickPurpose) cancelled") }

    // MARK: folders that stay readable between launches

    private func remember(_ url: URL) {
        guard let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else { return }
        var all = UserDefaults.standard.dictionary(forKey: "folderBookmarks") as? [String: Data] ?? [:]
        all[url.path] = data
        UserDefaults.standard.set(all, forKey: "folderBookmarks")
    }

    private func reopenFolders() {
        let all = UserDefaults.standard.dictionary(forKey: "folderBookmarks") as? [String: Data] ?? [:]
        var fresh: [String: Data] = [:]
        for (path, data) in all {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale),
                  url.startAccessingSecurityScopedResource() else { continue }
            accessed.append(url)
            fresh[url.path] = stale ? ((try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)) ?? data) : data
            _ = path
        }
        UserDefaults.standard.set(fresh, forKey: "folderBookmarks")
    }

    // MARK: web view

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { appLog("[nav] fail \(error)") }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { appLog("[nav] provisional fail \(error)") }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, ["http", "https"].contains(url.scheme) {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        } else {
            decisionHandler(.allow)
        }
    }
}
