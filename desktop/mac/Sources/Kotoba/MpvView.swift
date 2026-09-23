import Cocoa
import OpenGL.GL3
import CMpv

/// Video drawn by libmpv through its OpenGL render API (as IINA does). mpv doesn't draw subtitles:
/// Kotoba shows them in the web layer above so every word can be looked up.
final class MpvView: NSOpenGLView {
    private(set) var mpv: OpaquePointer?
    private var render: OpaquePointer?
    var onEvent: ((UnsafeMutablePointer<mpv_event>) -> Void)?
    private let eventQueue = DispatchQueue(label: "kotoba.mpv.events")

    override init(frame: NSRect) {
        let attrs: [NSOpenGLPixelFormatAttribute] = [
            NSOpenGLPixelFormatAttribute(NSOpenGLPFAOpenGLProfile), NSOpenGLPixelFormatAttribute(NSOpenGLProfileVersion3_2Core),
            NSOpenGLPixelFormatAttribute(NSOpenGLPFADoubleBuffer), NSOpenGLPixelFormatAttribute(NSOpenGLPFAAccelerated),
            NSOpenGLPixelFormatAttribute(NSOpenGLPFAAllowOfflineRenderers), 0,
        ]
        super.init(frame: frame, pixelFormat: NSOpenGLPixelFormat(attributes: attrs))!
        wantsBestResolutionOpenGLSurface = true
        openGLContext?.makeCurrentContext()
        var swap: GLint = 1
        openGLContext?.setValues(&swap, for: .swapInterval)
    }
    required init?(coder: NSCoder) { fatalError() }

    func start() {
        mpv = mpv_create()
        guard let mpv else { return }
        for (k, v) in [("vo", "libmpv"), ("hwdec", "auto-safe"), ("keep-open", "yes"), ("sub-visibility", "no"), ("secondary-sub-visibility", "no"),
                       ("input-default-bindings", "no"), ("input-vo-keyboard", "no"), ("osc", "no"), ("idle", "yes"), ("audio-display", "no")] {
            mpv_set_option_string(mpv, k, v)
        }
        mpv_initialize(mpv)
        openGLContext?.makeCurrentContext()
        var glInit = mpv_opengl_init_params(get_proc_address: { _, name in
            guard let name else { return nil }
            let symbol = CFStringCreateWithCString(kCFAllocatorDefault, name, CFStringBuiltInEncodings.ASCII.rawValue)
            let bundle = CFBundleGetBundleWithIdentifier("com.apple.opengl" as CFString)
            return CFBundleGetFunctionPointerForName(bundle, symbol)
        }, get_proc_address_ctx: nil)
        let api = UnsafeMutableRawPointer(mutating: (MPV_RENDER_API_TYPE_OPENGL as NSString).utf8String)
        withUnsafeMutablePointer(to: &glInit) { glPtr in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: api),
                mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: UnsafeMutableRawPointer(glPtr)),
                mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
            ]
            mpv_render_context_create(&render, mpv, &params)
        }
        mpv_render_context_set_update_callback(render, { ctx in
            let view = Unmanaged<MpvView>.fromOpaque(ctx!).takeUnretainedValue()
            DispatchQueue.main.async { view.needsDisplay = true }
        }, Unmanaged.passUnretained(self).toOpaque())
        mpv_set_wakeup_callback(mpv, { ctx in
            let view = Unmanaged<MpvView>.fromOpaque(ctx!).takeUnretainedValue()
            view.eventQueue.async { view.drainEvents() }
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    private func drainEvents() {
        guard let mpv else { return }
        while true {
            guard let e = mpv_wait_event(mpv, 0) else { return }
            if e.pointee.event_id == MPV_EVENT_NONE { return }
            onEvent?(e)
            if e.pointee.event_id == MPV_EVENT_SHUTDOWN { return }
        }
    }

    override func draw(_ dirtyRect: NSRect) {
        guard let render, let ctx = openGLContext else { return }
        ctx.makeCurrentContext()
        let size = convertToBacking(bounds).size
        var fbo = mpv_opengl_fbo(fbo: 0, w: Int32(size.width), h: Int32(size.height), internal_format: 0)
        var flip: Int32 = 1
        withUnsafeMutablePointer(to: &fbo) { fboPtr in
            withUnsafeMutablePointer(to: &flip) { flipPtr in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: UnsafeMutableRawPointer(fboPtr)),
                    mpv_render_param(type: MPV_RENDER_PARAM_FLIP_Y, data: UnsafeMutableRawPointer(flipPtr)),
                    mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil),
                ]
                mpv_render_context_render(render, &params)
            }
        }
        ctx.flushBuffer()
    }

    override func reshape() { super.reshape(); needsDisplay = true }

    // MARK: commands

    func command(_ args: [String]) {
        guard let mpv else { return }
        var cargs = args.map { strdup($0) } + [nil]
        cargs.withUnsafeMutableBufferPointer { buf in
            buf.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buf.count) { _ = mpv_command(mpv, $0) }
        }
        for p in cargs { free(p) }
    }
    func set(_ name: String, _ value: String) { if let mpv { mpv_set_property_string(mpv, name, value) } }
    func get(_ name: String) -> String? {
        guard let mpv, let c = mpv_get_property_string(mpv, name) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }
    func observe(_ name: String, _ format: mpv_format = MPV_FORMAT_DOUBLE) { if let mpv { mpv_observe_property(mpv, 0, name, format) } }

    func shutdown() {
        if let render { mpv_render_context_set_update_callback(render, nil, nil); mpv_render_context_free(render) }
        render = nil
        if let mpv { mpv_terminate_destroy(mpv) }
        mpv = nil
    }
}
