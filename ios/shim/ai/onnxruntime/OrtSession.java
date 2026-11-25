package ai.onnxruntime;
import java.util.Map;
import java.util.Set;
public class OrtSession implements AutoCloseable {
    public static class SessionOptions {
        public void setIntraOpNumThreads(int n) throws OrtException {}
        public void setInterOpNumThreads(int n) throws OrtException {}
        public void setCPUArenaAllocator(boolean on) throws OrtException {}
        public void setMemoryPatternOptimization(boolean on) throws OrtException {}
    }
    public static class Result implements AutoCloseable, Iterable<Map.Entry<String,OnnxTensor>> {
        public OnnxTensor get(int i){return null;}
        @Override public java.util.Iterator<Map.Entry<String,OnnxTensor>> iterator(){return java.util.Collections.emptyIterator();}
        @Override public void close(){}
    }
    public Set<String> getInputNames(){return java.util.Collections.emptySet();}
    public Result run(Map<String,OnnxTensor> inputs) throws OrtException {throw new OrtException("ONNX Runtime is not part of the iOS build");}
    @Override public void close(){}
}
