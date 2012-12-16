package zu.finagle.http.test;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;

import org.apache.zookeeper.server.NIOServerCnxn;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import zu.core.cluster.PartitionInfoReader;
import zu.core.cluster.ZuCluster;
import zu.core.cluster.ZuClusterEventListener;
import zu.core.cluster.util.Util;
import zu.finagle.ZuFinagleService;
import zu.finagle.http.ZuFinagleHttpServiceFactory;

import com.twitter.common.zookeeper.ServerSet.EndpointStatus;
import com.twitter.finagle.Service;
import com.twitter.finagle.builder.Server;
import com.twitter.finagle.builder.ServerBuilder;
import com.twitter.finagle.http.Http;
import com.twitter.util.Future;

public class ZuFinagleHttpTest {
  
  static Server buildLocalServer(int port, final String respString){
    Service<HttpRequest, HttpResponse> svc = new Service<HttpRequest,HttpResponse>(){

      @Override
      public Future<HttpResponse> apply(HttpRequest req) {
        HttpResponse httpResponse =
            new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
          httpResponse.setContent(ChannelBuffers.wrappedBuffer(respString.getBytes()));
          return Future.value(httpResponse);
      }
    };
    
    return ServerBuilder.safeBuild(svc,
        ServerBuilder.get()
                     .codec(Http.get())
                     .name("HTTPServer")
                     .bindTo(new InetSocketAddress("localhost", port)));
  }

  @Test
  public void testBasic() throws Exception{
    
    final String val = "test";
    final int port = 4444;
    
    buildLocalServer(port,val);
    
    // building client via zu
    
    ZuFinagleHttpServiceFactory clientFacotry = new ZuFinagleHttpServiceFactory(1, 1000);
    ZuFinagleService<HttpRequest,HttpResponse> zuClient = clientFacotry.getService(new InetSocketAddress("localhost",port));
    
    Service<HttpRequest,HttpResponse> finagleClient = zuClient.getFinagleSvc();
    
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    
    HttpResponse response = finagleClient.apply(request).get();
    
    String resStr = new String(response.getContent().array());
    
    TestCase.assertEquals(val, resStr);
  }
  
  @Test
  public void testIntegration() throws Exception{
    int zkport = 11111;
    File dir = new File("/tmp/zu-finagle-test");
    NIOServerCnxn.Factory zk = Util.startZkServer(zkport, dir);
   
    final Map<Integer,List<Integer>> partitionLayout = new HashMap<Integer,List<Integer>>();
    partitionLayout.put(10001, Arrays.asList(0));
    partitionLayout.put(10002, Arrays.asList(1));
    partitionLayout.put(10003, Arrays.asList(2));
    
    PartitionInfoReader partitionInfoReader = new PartitionInfoReader() {
      
      @Override
      public List<Integer> getPartitionFor(InetSocketAddress addr) {
        return partitionLayout.get(addr.getPort());
      }
    };
    
    ZuCluster<ZuFinagleService<HttpRequest, HttpResponse>> cluster = 
        new ZuCluster<ZuFinagleService<HttpRequest, HttpResponse>>(new InetSocketAddress("localhost",zkport),
        partitionInfoReader, new ZuFinagleHttpServiceFactory(10), "test-finagle-cluster");
    
    final AtomicReference<Map<Integer, ArrayList<ZuFinagleService<HttpRequest, HttpResponse>>>> clusterViewRef = new AtomicReference<Map<Integer, ArrayList<ZuFinagleService<HttpRequest, HttpResponse>>>>(null);
    cluster.addClusterEventListener(new ZuClusterEventListener<ZuFinagleService<HttpRequest,HttpResponse>>() {
      
      @Override
      public void clusterChanged(
          Map<Integer, ArrayList<ZuFinagleService<HttpRequest, HttpResponse>>> clusterView) {
        clusterViewRef.set(clusterView);
      }
    });
    
    buildLocalServer(10001,"0");
    EndpointStatus e1 = cluster.join(new InetSocketAddress("localhost",10001));
    buildLocalServer(10002,"1");
    EndpointStatus e2 = cluster.join(new InetSocketAddress("localhost",10002));
    buildLocalServer(10003,"2");
    EndpointStatus e3 = cluster.join(new InetSocketAddress("localhost",10003));
   
    Map<Integer, ArrayList<ZuFinagleService<HttpRequest, HttpResponse>>> clusterView;
    while(true){
      clusterView = clusterViewRef.get();
      if (clusterView != null && clusterView.size() == 3) break;
      Thread.sleep(10);
    }
    
    Set<Integer> answer = new HashSet<Integer>(Arrays.asList(0,1,2));
    Set<Integer> retValues = new HashSet<Integer>();
    

    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    
    try{
      for (Entry<Integer,ArrayList<ZuFinagleService<HttpRequest, HttpResponse>>> entry : clusterView.entrySet()){
        Integer part = entry.getKey();
        ZuFinagleService<HttpRequest, HttpResponse> zuSvc = entry.getValue().get(0);
        Service<HttpRequest,HttpResponse> finalgeSvc = zuSvc.getFinagleSvc();
        HttpResponse resp = finalgeSvc.apply(request).get();
        String rs = new String(resp.getContent().array());
        
        int retVal = Integer.parseInt(rs);
        TestCase.assertEquals(part.intValue(), retVal);
        
        retValues.add(retVal);
      }
      
      TestCase.assertEquals(answer, retValues);
    }
    finally{
      cluster.leave(e1);
      cluster.leave(e2);
      cluster.leave(e3);
      zk.shutdown();
      Util.rmDir(dir);
    }
     
    
  }
}
