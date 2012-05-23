package com.tinkerpop.blueprints.util.wrappers.batch;

import com.tinkerpop.blueprints.*;
import com.tinkerpop.blueprints.impls.tg.IgnoreIdTinkerGraph;
import com.tinkerpop.blueprints.impls.tg.MockTransactionalGraph;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import junit.framework.TestCase;

/**
 * Tests {@link BatchGraph} by creating a variable length chain and verifying that the chain is correctly inserted into the wrapped TinkerGraph.
 *
 * Tests the various different Vertex caches and different length of chains.
 * 
 * (c) Matthias Broecheler (http://www.matthiasb.com)
 */

public class BatchGraphTest extends TestCase {
    
    private static final String UID = "uid";
    
    private static final String vertexIDKey = "vid";
    private static final String edgeIDKey = "eid";
    private static boolean assignKeys = false;
    private static boolean ignoreIDs = false;

    public void testNumberIDLoading() {
        loadingTest(5000,100, BatchGraph.IDType.NUMBER,new NumberLoadingFactory());
        loadingTest(200000,10000, BatchGraph.IDType.NUMBER,new NumberLoadingFactory());

        assignKeys=true;
        loadingTest(5000,100, BatchGraph.IDType.NUMBER,new NumberLoadingFactory());
        loadingTest(50000,10000, BatchGraph.IDType.NUMBER,new NumberLoadingFactory());
        assignKeys=false;

        ignoreIDs=true;
        loadingTest(5000,100, BatchGraph.IDType.NUMBER,new NumberLoadingFactory());
        loadingTest(50000,10000, BatchGraph.IDType.NUMBER,new NumberLoadingFactory());
        ignoreIDs=false;
    }

    public void testObjectIDLoading() {
        loadingTest(5000,100, BatchGraph.IDType.OBJECT,new StringLoadingFactory());
        loadingTest(200000,10000, BatchGraph.IDType.OBJECT,new StringLoadingFactory());
    }

    public void testStringIDLoading() {
        loadingTest(5000,100, BatchGraph.IDType.STRING,new StringLoadingFactory());
        loadingTest(200000,10000, BatchGraph.IDType.STRING,new StringLoadingFactory());
    }

    public void testURLIDLoading() {
        loadingTest(5000,100, BatchGraph.IDType.URL,new URLLoadingFactory());
        loadingTest(200000,10000, BatchGraph.IDType.URL,new URLLoadingFactory());
    }


    public void loadingTest(int total, int bufferSize, BatchGraph.IDType type, LoadingFactory ids) {
        final VertexEdgeCounter counter = new VertexEdgeCounter();
        MockTransactionalGraph tgraph = null;
        if (ignoreIDs) {
            tgraph = new MockTransactionalGraph(new IgnoreIdTinkerGraph());
        } else {
            tgraph = new MockTransactionalGraph(new TinkerGraph());
        }

        BLGraph graph = new BLGraph(tgraph,counter,ids);
        BatchGraph<BLGraph> loader = new BatchGraph<BLGraph>(graph,type,bufferSize);
        if (assignKeys) {
            loader.setVertexIDKey(vertexIDKey);
            loader.setEdgeIDKey(edgeIDKey);
        }

        //Create a chain
        int chainLength = total;
        Vertex previous = null;
        for (int i=0;i<=chainLength;i++) {
            Vertex next = loader.addVertex(ids.getVertexID(i));
            next.setProperty(UID,i);
            counter.numVertices++;
            counter.totalVertices++;
            if (previous!=null) {
                Edge e = loader.addEdge(ids.getEdgeID(i), loader.getVertex(previous.getId()), loader.getVertex(next.getId()), "next");
                e.setProperty(UID,i);
                counter.numEdges++;
            }
            previous = next;
        }

        loader.stopTransaction(TransactionalGraph.Conclusion.SUCCESS);
        assertTrue(tgraph.allSuccessful());
        assertTrue(tgraph.allFinished());
        loader.shutdown();
    }
    
    static class VertexEdgeCounter {
        
        int numVertices = 0;
        int numEdges = 0;
        int totalVertices = 0;
        
    }
    
    static class BLGraph implements TransactionalGraph {
        
        private static final int keepLast = 10;
        
        private final VertexEdgeCounter counter;
        private boolean first=true;
        private final LoadingFactory ids;

        private final TransactionalGraph graph;
                
        BLGraph(TransactionalGraph graph, final VertexEdgeCounter counter, LoadingFactory ids) {
            this.graph=graph;
            this.counter=counter;
            this.ids=ids;
        }
        
        private static final Object parseID(Object id) {
            if (id instanceof String) {
                try {
                    return Integer.parseInt((String) id);
                } catch (NumberFormatException e) {
                    return id;
                }
            } else return id;
        }

        @Override
        public void startTransaction() throws IllegalStateException {
            graph.startTransaction();
        }

        @Override
        public void stopTransaction(Conclusion conclusion) {
            graph.stopTransaction(conclusion);
            //System.out.println("Committed (vertices/edges): " + counter.numVertices + " / " + counter.numEdges);
            assertEquals(counter.numVertices, BaseTest.count(graph.getVertices()) - (first ? 0 : keepLast));
            assertEquals(counter.numEdges,BaseTest.count(graph.getEdges()));
            for (Edge e : getEdges()) {
                int id = ((Number)e.getProperty(UID)).intValue();
                if (!ignoreIDs) {
                    assertEquals(ids.getEdgeID(id),parseID(e.getId()));
                }
                assertEquals(1,(Integer)e.getVertex(Direction.IN).getProperty(UID)-(Integer)e.getVertex(Direction.OUT).getProperty(UID));
                if (assignKeys) {
                    assertEquals(ids.getEdgeID(id),e.getProperty(edgeIDKey));
                }
            }
            for (Vertex v : getVertices()) {
                int id = ((Number)v.getProperty(UID)).intValue();
                if (!ignoreIDs) {
                    assertEquals(ids.getVertexID(id),parseID(v.getId()));
                }
                assertTrue(2>=BaseTest.count(v.getEdges(Direction.BOTH)));
                assertTrue(1>=BaseTest.count(v.getEdges(Direction.IN)));
                assertTrue(1>=BaseTest.count(v.getEdges(Direction.OUT)));

                if (assignKeys) {
                    assertEquals(ids.getVertexID(id), v.getProperty(vertexIDKey));
                }

            }
            for (Vertex v : getVertices()) {
                int id = ((Number)v.getProperty(UID)).intValue();
                if (id<counter.totalVertices-keepLast) {
                    removeVertex(v);
                }
            }
            for (Edge e : getEdges()) removeEdge(e);
            assertEquals(keepLast,BaseTest.count(graph.getVertices()));
            counter.numVertices=0;
            counter.numEdges=0;
            first = false;
            //System.out.println("------");
        }

        @Override
        public Features getFeatures() {
            return graph.getFeatures();
        }

        @Override
        public Vertex addVertex(Object id) {
            return graph.addVertex(id);
        }

        @Override
        public Vertex getVertex(Object id) {
            return graph.getVertex(id);
        }

        @Override
        public void removeVertex(Vertex vertex) {
            graph.removeVertex(vertex);
        }

        @Override
        public Iterable<Vertex> getVertices() {
            return graph.getVertices();
        }

        @Override
        public Iterable<Vertex> getVertices(String key, Object value) {
            return graph.getVertices(key,value);
        }

        @Override
        public Edge addEdge(Object id, Vertex outVertex, Vertex inVertex, String label) {
            return graph.addEdge(id,outVertex,inVertex,label);
        }

        @Override
        public Edge getEdge(Object id) {
            return graph.getEdge(id);
        }

        @Override
        public void removeEdge(Edge edge) {
            graph.removeEdge(edge);
        }

        @Override
        public Iterable<Edge> getEdges() {
            return graph.getEdges();
        }

        @Override
        public Iterable<Edge> getEdges(String key, Object value) {
            return graph.getEdges(key,value);
        }

        @Override
        public void shutdown() {
            graph.shutdown();
        }

    }
    
    interface LoadingFactory {
        
        public Object getVertexID(int id);
        
        public Object getEdgeID(int id);
        
    }
    
    class StringLoadingFactory implements LoadingFactory {

        @Override
        public Object getVertexID(int id) {
            return "V" + id;
        }

        @Override
        public Object getEdgeID(int id) {
            return "E" + id;
        }
    }
    
    class NumberLoadingFactory implements LoadingFactory {

        @Override
        public Object getVertexID(int id) {
            return Integer.valueOf(id*2);
        }

        @Override
        public Object getEdgeID(int id) {
            return Integer.valueOf(id*2+1);
        }
    }

    class URLLoadingFactory implements LoadingFactory {

        @Override
        public Object getVertexID(int id) {
            return "http://www.tinkerpop.com/rdf/ns/vertex/" + id;
        }

        @Override
        public Object getEdgeID(int id) {
            return "http://www.tinkerpop.com/rdf/ns/edge#" + id;
        }
    }
 
}
