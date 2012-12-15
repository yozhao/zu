package zu.core.test;

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
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase;

import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import zu.core.cluster.ZuCluster;
import zu.core.cluster.ZuClusterEventListener;

import com.twitter.common.zookeeper.ServerSet.EndpointStatus;


public class ZuTest {
  
  static int zkport = 21818;

  static NIOServerCnxn.Factory standaloneServerFactory;
  static ZooKeeperServer server;
  static File dir;
  
  private static void rmDir(File dir){
    if (dir.isDirectory()){
      File[] files = dir.listFiles();
      for (File f : files){
        rmDir(f);
      }
    }
    dir.delete();
  }
  
  @BeforeClass
  public static void init() throws Exception{
    int numConnections = 10;
    int tickTime = 2000;

    dir = new File("/tmp/zookeeper").getAbsoluteFile();
    System.out.println("dir: "+dir);

    server = new ZooKeeperServer(dir, dir, tickTime);
    standaloneServerFactory = new NIOServerCnxn.Factory(new InetSocketAddress(zkport), numConnections);
    standaloneServerFactory.startup(server); 
  }
  
  static void validate(Map<Integer,Set<Integer>> expected, Map<Integer,ArrayList<MockZuService>> view){
    for (Entry<Integer,ArrayList<MockZuService>> entry : view.entrySet()){
      Integer key = entry.getKey();
      ArrayList<MockZuService> list = entry.getValue();
      HashSet<Integer> parts = new HashSet<Integer>();
      for (MockZuService svc : list){
        List<Integer> partitionList = MockZuService.PartitionReader.getPartitionFor(svc.getAddress());
        parts.addAll(partitionList);
      }
      TestCase.assertTrue(parts.contains(key));
      Set<Integer> expectedSet = expected.remove(key);
      TestCase.assertNotNull(expectedSet);
      TestCase.assertEquals(expectedSet, parts);
    }
    TestCase.assertTrue(expected.isEmpty());
  }
  
  @Test
  public void testBasic() throws Exception{
    ZuCluster<MockZuService> mockCluster = new ZuCluster<MockZuService>(new InetSocketAddress(zkport), MockZuService.PartitionReader, MockZuService.Factory, "/testcluster1");
    
    MockZuService s1 = new MockZuService(new InetSocketAddress(1));
    
    final Map<Integer,Set<Integer>> answer = new HashMap<Integer,Set<Integer>>();
    
    answer.put(0, new HashSet<Integer>(Arrays.asList(0,1)));
    answer.put(1, new HashSet<Integer>(Arrays.asList(0,1)));
    
    
    
    final AtomicBoolean flag = new AtomicBoolean(false);
    
    mockCluster.addClusterEventListener(new ZuClusterEventListener<MockZuService>() {
      
      @Override
      public void clusterChanged(Map<Integer, ArrayList<MockZuService>> clusterView) {
        validate(answer,clusterView);
        flag.set(true);
      }
    });

   
    EndpointStatus e1 = mockCluster.join(s1);
    
    while(!flag.get()){
      Thread.sleep(10);
    }
    
    mockCluster.leave(e1);
  }
  
  
  @AfterClass
  public static void tearDown(){
    server.shutdown();
    standaloneServerFactory.shutdown();
    rmDir(dir);
  }

}
