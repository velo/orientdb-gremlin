package org.apache.tinkerpop.gremlin.orientdb;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.util.OCallable;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.db.ODatabaseFactory;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.index.OIndexManager;
import com.orientechnologies.orient.core.index.OPropertyIndexDefinition;
import com.orientechnologies.orient.core.iterator.ORecordIteratorClass;
import com.orientechnologies.orient.core.metadata.schema.*;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentInternal;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.orientdb.traversal.strategy.optimization.OrientGraphStepStrategy;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.apache.tinkerpop.gremlin.orientdb.StreamUtils.asStream;

@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
public final class OrientGraph implements Graph {
    static {
        TraversalStrategies.GlobalCache.registerStrategies(
            OrientGraph.class,
            TraversalStrategies.GlobalCache.getStrategies(Graph.class).clone()
                .addStrategies(OrientGraphStepStrategy.instance()));
    }

    public static String CONFIG_URL = "orient-url";
    public static String CONFIG_USER = "orient-user";
    public static String CONFIG_PASS = "orient-pass";
    public static String CONFIG_CREATE = "orient-create";
    public static String CONFIG_OPEN = "orient-open";

    protected final ODatabaseDocumentTx database;
    protected final String url;

    public static OrientGraph open(final Configuration configuration) {
        return new OrientGraph(configuration);
    }

    public OrientGraph(Configuration config) {
        this.url = config.getString(CONFIG_URL, "memory:test-" + Math.random());
        this.database = getDatabase(url,
            config.getString(CONFIG_USER, "admin"),
            config.getString(CONFIG_PASS, "admin"),
            config.getBoolean(CONFIG_CREATE, true),
            config.getBoolean(CONFIG_OPEN, true));
    }

    public OrientGraph(ODatabaseDocumentTx database) {
        this.url = database.getURL();
        this.database = database;
    }

    @Override
    public Features features() {
        return ODBFeatures.OrientFeatures.INSTANCE;
    }

    public void makeActive() {
//        activeGraph.set(this);

        final ODatabaseDocument tlDb = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
        if (database != null && tlDb != database) {
            database.activateOnCurrentThread();
            ODatabaseRecordThreadLocal.INSTANCE.set(database);
        }
    }

    /**
     * @param create if true automatically creates database if database with given URL does not exist
     * @param open   if true automatically opens the database
     */
    protected ODatabaseDocumentTx getDatabase(String url, String user, String password, boolean create, boolean open) {
        final ODatabaseDocumentTx db = new ODatabaseFactory().createDatabase("graph", url);
        if (!db.getURL().startsWith("remote:") && !db.exists()) {
            if (create) db.create();
            else if (open) throw new ODatabaseException("Database '" + url + "' not found");
        } else if (open) db.open(user, password);

        return db;
    }

    @Override
    public Vertex addVertex(Object... keyValues) {
        makeActive();
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        if (ElementHelper.getIdValue(keyValues).isPresent()) throw Vertex.Exceptions.userSuppliedIdsNotSupported();

        String label = ElementHelper.getLabelValue(keyValues).orElse(OImmutableClass.VERTEX_CLASS_NAME);
        String className = label.equals(OImmutableClass.VERTEX_CLASS_NAME) ?
            OImmutableClass.VERTEX_CLASS_NAME :
            OImmutableClass.VERTEX_CLASS_NAME + "_" + label;
        OrientVertex vertex = new OrientVertex(this, className);
        ElementHelper.attachProperties(vertex, keyValues);

        vertex.save();
        return vertex;
    }

    public Object executeSql(String sql) {
        makeActive();
        OCommandRequest command = database.command(new OCommandSQL(sql));
        return command.execute();
    }

    public Object executeCommand(OCommandRequest command) {
        return command.execute();
    }

    @Override
    public <C extends GraphComputer> C compute(Class<C> graphComputerClass) throws IllegalArgumentException {
        throw new NotImplementedException();
    }

    @Override
    public GraphComputer compute() throws IllegalArgumentException {
        throw new NotImplementedException();
    }

    @Override
    public Iterator<Vertex> vertices(Object... vertexIds) {
        makeActive();
        return elements(
            OImmutableClass.VERTEX_CLASS_NAME,
            r -> new OrientVertex(this, getRawDocument(r)),
            vertexIds);
    }

    protected Object convertKey(final OIndex<?> idx, Object iValue) {
        if (iValue != null) {
            final OType[] types = idx.getKeyTypes();
            if (types.length == 0)
                iValue = iValue.toString();
            else
                iValue = OType.convert(iValue, types[0].getDefaultJavaType());
        }
        return iValue;
    }

    public Stream<OrientVertex> getIndexedVertices(OrientIndexQuery indexReference) {
        makeActive();

//        if (iKey.equals("@class"))
//            return getVerticesOfClass(iValue.toString());

        final OIndex<?> idx = database.getMetadata().getIndexManager().getIndex(indexReference.indexName());
        Object iValue = indexReference.value;
        if (idx == null) {
            // NO INDEX
            return Collections.<OrientVertex>emptyList().stream();
        } else {
            iValue = convertKey(idx, iValue);
            Object indexValue = idx.get(iValue);
            if (indexValue == null) {
                return Collections.<OrientVertex>emptyList().stream();
            } else if (!(indexValue instanceof Iterable<?>)) {
                indexValue = Collections.singletonList(indexValue);
            }
            Iterable<ORecordId> iterableIds = (Iterable<ORecordId>) indexValue;
            Stream<ORecordId> ids = StreamSupport.stream(iterableIds.spliterator(), false);
            Stream<ORecord> records = ids.map(id -> (ORecord) id.getRecord()).filter(r -> r != null);
            return records.map(r -> new OrientVertex(this, getRawDocument(r)));
        }
    }

    private OIndexManager getIndexManager() {
        return database.getMetadata().getIndexManager();
    }


    public Set<String> getIndexedKeys(String className) {
        Iterator<OIndex<?>> indexes = getIndexManager().getClassIndexes(className).iterator();
        HashSet<String> indexedKeys = new HashSet<>();
        indexes.forEachRemaining(index -> {
            index.getDefinition().getFields().forEach(indexedKeys::add);
        });
        return indexedKeys;
    }

    public Set<String> getIndexedKeys(final Class<? extends Element> elementClass, String label) {
        if (Vertex.class.isAssignableFrom(elementClass)) {
            return getVertexIndexedKeys(label);
        } else if (Edge.class.isAssignableFrom(elementClass)) {
            return getEdgeIndexedKeys(label);
        } else {
            throw new IllegalArgumentException("Class is not indexable: " + elementClass);
        }
    }

    public Set<String> getIndexedKeys(final Class<? extends Element> elementClass) {
        if (Vertex.class.isAssignableFrom(elementClass)) {
            return getIndexedKeys(OImmutableClass.VERTEX_CLASS_NAME);
        } else if (Edge.class.isAssignableFrom(elementClass)) {
            return getIndexedKeys(OImmutableClass.EDGE_CLASS_NAME);
        } else {
            throw new IllegalArgumentException("Class is not indexable: " + elementClass);
        }
    }

    public Set<String> getVertexIndexedKeys(final String label) {
        return getIndexedKeys(OImmutableClass.VERTEX_CLASS_NAME + "_" + label);
    }

    public Set<String> getEdgeIndexedKeys(final String label) {
        return getIndexedKeys(OImmutableClass.EDGE_CLASS_NAME + "_" + label);
    }

    @Override
    public Iterator<Edge> edges(Object... edgeIds) {
        makeActive();
        return elements(
            OImmutableClass.EDGE_CLASS_NAME,
            r -> new OrientEdge(this, getRawDocument(r)),
            edgeIds);
    }

    protected <A extends Element> Iterator<A> elements(String elementClass, Function<ORecord, A> toA, Object... elementIds) {
        boolean polymorphic = true;
        if (elementIds.length == 0) {
            // return all vertices as stream
            Iterator<ORecord> itty = new ORecordIteratorClass<>(database, database, elementClass, polymorphic);
            return asStream(itty).map(toA).iterator();
        } else {
            Stream<ORecordId> ids = Stream.of(elementIds).map(OrientGraph::createRecordId);
            Stream<ORecord> records = ids.filter(ORecordId::isValid).map(id -> (ORecord) id.getRecord()).filter(r -> r != null);
            return records.map(toA).iterator();
        }
    }

    protected static ORecordId createRecordId(Object id) {
        if (id instanceof ORecordId)
            return (ORecordId) id;
        else if (id instanceof String)
            return new ORecordId((String) id);
        else
            throw new IllegalArgumentException("Orient IDs have to be a String or ORecordId - you provided a " + id.getClass());
    }

    protected ODocument getRawDocument(ORecord record) {
        if (record == null) throw new NoSuchElementException();
        if (record instanceof OIdentifiable)
            record = record.getRecord();
        ODocument currentDocument = (ODocument) record;
        if (currentDocument.getInternalStatus() == ODocument.STATUS.NOT_LOADED)
            currentDocument.load();
        if (ODocumentInternal.getImmutableSchemaClass(currentDocument) == null)
            throw new IllegalArgumentException(
                "Cannot determine the graph element type because the document class is null. Probably this is a projection, use the EXPAND() function");
        return currentDocument;
    }

    @Override
    public Transaction tx() {
        makeActive();
        throw new NotImplementedException();
    }

    @Override
    public Variables variables() {
        makeActive();
        throw new NotImplementedException();
    }

    @Override
    public Configuration configuration() {
        makeActive();
        throw new NotImplementedException();
    }

    @Override
    public void close() throws Exception {
        makeActive();
        boolean commitTx = true;

        try {
            if (!database.isClosed() && commitTx) {
                final OStorage storage = database.getStorage();
                if (storage instanceof OAbstractPaginatedStorage) {
                    if (((OAbstractPaginatedStorage) storage).getWALInstance() != null)
                        database.commit();
                }

            }

        } catch (RuntimeException e) {
            OLogManager.instance().info(this, "Error during context close for db " + url, e);
            throw e;
        } catch (Exception e) {
            OLogManager.instance().error(this, "Error during context close for db " + url, e);
            throw new OException("Error during context close for db " + url, e);
        } finally {
            try {
                database.close();
            } catch (Exception e) {
                OLogManager.instance().error(this, "Error during context close for db " + url, e);
            }
        }
    }

    public void createVertexClass(final String className) {
        makeActive();
        createClass(className, OImmutableClass.VERTEX_CLASS_NAME);
    }

    public void createEdgeClass(final String className) {
        makeActive();
        createClass(className, OImmutableClass.EDGE_CLASS_NAME);
    }

    public void createClass(final String className, final String superClassName) {
        makeActive();
        OClass superClass = database.getMetadata().getSchema().getClass(superClassName);
        if (superClass == null) {
            Collection<OClass> allClasses = database.getMetadata().getSchema().getClasses();
            throw new IllegalArgumentException("unable to find class " + superClassName + ". Available classes: " + allClasses);
        }
        createClass(className, superClass);
    }

    public void createClass(final String className, final OClass superClass) {
        makeActive();
        OSchemaProxy schema = database.getMetadata().getSchema();
        if (schema.getClass(className) == null) {
            try
            {
              schema.createClass(className, superClass);
            }
            catch (OException e)
            {
              throw new IllegalArgumentException(e);
            }
            OLogManager.instance().info(this, "created class '" + className + "' as subclass of '" + superClass + "'");
        }
    }

    public ODatabaseDocumentTx getRawDatabase() {
        makeActive();
        return database;
    }

    /**
     * Returns the persistent class for type iTypeName as OrientEdgeType instance.
     *
     * @param iTypeName Edge class name
     */
    public final OrientEdgeType getEdgeType(final String iTypeName) {
        makeActive();
        final OClass cls = getRawDatabase().getMetadata().getSchema().getClass(iTypeName);
        if (cls == null)
            return null;

        OrientEdgeType.checkType(cls);
        return new OrientEdgeType(this, cls);
    }

    protected <T> String getClassName(final Class<T> elementClass) {
        if (elementClass.isAssignableFrom(Vertex.class))
            return OrientVertexType.CLASS_NAME;
        else if (elementClass.isAssignableFrom(Edge.class))
            return OrientEdgeType.CLASS_NAME;
        throw new IllegalArgumentException("Class '" + elementClass + "' is neither a Vertex, nor an Edge");
    }

    protected void prepareIndexConfiguration(Configuration config) {
        String defaultIndexType = OClass.INDEX_TYPE.NOTUNIQUE.name();
        OType defaultKeyType = OType.STRING;
        String defaultClassName = null;
        String defaultCollate = null;
        ODocument defaultMetadata = null;

        if (!config.containsKey("type"))
            config.setProperty("type", defaultIndexType);
        if (!config.containsKey("keytype"))
            config.setProperty("keytype", defaultKeyType);
        if (!config.containsKey("class"))
            config.setProperty("class", defaultClassName);
        if (!config.containsKey("collate"))
            config.setProperty("collate", defaultCollate);
        if (!config.containsKey("metadata"))
            config.setProperty("metadata", defaultMetadata);
    }

    /**
     * Creates an automatic indexing structure for indexing provided key for element class.
     *
     * @param key           the key to create the index for
     * @param label         the element label
     * @param configuration a collection of parameters for the underlying index implementation:
     *                      <ul>
     *                      <li>"type" is the index type between the supported types (UNIQUE, NOTUNIQUE, FULLTEXT). The default type is NOT_UNIQUE
     *                      <li>"class" is the class to index when it's a custom type derived by Vertex (V) or Edge (E)
     *                      <li>"keytype" to use a key type different by OType.STRING,</li>
     *                      </li>
     *                      </ul>
     * @param <T>           the element class specification
     */
    public <T extends Element> void createVertexIndex(final String key, final String label, final Configuration configuration) {
        String className = OrientVertexType.CLASS_NAME + "_" + label;
        createVertexClass(className);
        createIndex(key, className, configuration);
    }

    /**
     * Creates an automatic indexing structure for indexing provided key for element class.
     *
     * @param key           the key to create the index for
     * @param label         the element label
     * @param configuration a collection of parameters for the underlying index implementation:
     *                      <ul>
     *                      <li>"type" is the index type between the supported types (UNIQUE, NOTUNIQUE, FULLTEXT). The default type is NOT_UNIQUE
     *                      <li>"class" is the class to index when it's a custom type derived by Vertex (V) or Edge (E)
     *                      <li>"keytype" to use a key type different by OType.STRING,</li>
     *                      </li>
     *                      </ul>
     * @param <T>           the element class specification
     */
    public <T extends Element> void createEdgeIndex(final String key, final String label, final Configuration configuration) {
        String className = OrientEdgeType.CLASS_NAME + "_" + label;
        createEdgeClass(className);
        createIndex(key, className, configuration);
    }

    @SuppressWarnings({"rawtypes"})
    private <T extends Element> void createIndex(final String key, String className, final Configuration configuration) {
        makeActive();

        prepareIndexConfiguration(configuration);

        OCallable<OClass, OrientGraph> callable = new OCallable<OClass, OrientGraph>() {
            @Override
            public OClass call(final OrientGraph g) {

                String indexType = configuration.getString("type");
                OType keyType = (OType) configuration.getProperty("keytype");
                String collate = configuration.getString("collate");
                ODocument metadata = (ODocument) configuration.getProperty("metadata");


                final ODatabaseDocumentTx db = getRawDatabase();
                final OSchema schema = db.getMetadata().getSchema();

                final OClass cls = schema.getClass(className);
                final OProperty property = cls.getProperty(key);
                if (property != null)
                    keyType = property.getType();

                OPropertyIndexDefinition indexDefinition = new OPropertyIndexDefinition(className, key, keyType);
                if (collate != null)
                    indexDefinition.setCollate(collate);
                db.getMetadata().getIndexManager()
                    .createIndex(className + "." + key, indexType, indexDefinition, cls.getPolymorphicClusterIds(), null, metadata);
                return null;
            }
        };
        execute(callable, "create key index on '", className, ".", key, "'");
    }

    public <RET> RET execute(final OCallable<RET, OrientGraph> iCallable, final String... iOperationStrings) throws RuntimeException {
        makeActive();

        if (OLogManager.instance().isWarnEnabled() && iOperationStrings.length > 0) {
            // COMPOSE THE MESSAGE
            final StringBuilder msg = new StringBuilder(256);
            for (String s : iOperationStrings)
                msg.append(s);

            // ASSURE PENDING TX IF ANY IS COMMITTED
            OLogManager.instance().warn(
                this,
                msg.toString());
        }
        return iCallable.call(this);
    }

}
