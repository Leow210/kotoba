package ai.onnxruntime;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
/** Not available on iOS: comic text is read by Apple Vision there. Kept so the shared OCR classes translate. */
public class OnnxTensor implements AutoCloseable {
    public static OnnxTensor createTensor(OrtEnvironment env,FloatBuffer data,long[] shape) throws OrtException {throw new OrtException("ONNX Runtime is not part of the iOS build");}
    public static OnnxTensor createTensor(OrtEnvironment env,LongBuffer data,long[] shape) throws OrtException {throw new OrtException("ONNX Runtime is not part of the iOS build");}
    public FloatBuffer getFloatBuffer(){return FloatBuffer.allocate(0);}
    public TensorInfo getInfo(){return new TensorInfo();}
    public Object getValue() throws OrtException {return null;}
    @Override public void close(){}
}
