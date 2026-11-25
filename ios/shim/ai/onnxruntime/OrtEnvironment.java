package ai.onnxruntime;
public class OrtEnvironment {
    public static OrtEnvironment getEnvironment() throws OrtException {throw new OrtException("ONNX Runtime is not part of the iOS build");}
    public OrtSession createSession(String path,OrtSession.SessionOptions options) throws OrtException {throw new OrtException("ONNX Runtime is not part of the iOS build");}
}
