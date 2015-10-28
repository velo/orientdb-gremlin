package org.apache.tinkerpop.gremlin.orientdb;

import com.orientechnologies.common.collection.OMultiCollectionIterator;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ORecordLazyList;
import com.orientechnologies.orient.core.db.record.ORecordLazyMultiValue;
import com.orientechnologies.orient.core.db.record.ridbag.ORidBag;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OImmutableClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentInternal;

import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public final class OrientVertex extends OrientElement implements Vertex {
    public static final String CONNECTION_OUT_PREFIX = OrientGraphUtils.CONNECTION_OUT + "_";
    public static final String CONNECTION_IN_PREFIX  = OrientGraphUtils.CONNECTION_IN + "_";

    public OrientVertex(final OrientGraph graph, final OIdentifiable rawElement) {
        super(graph, rawElement);
    }

    public OrientVertex(OrientGraph graph, String className) {
        super(graph, createRawElement(graph, className));
    }

    protected static ODocument createRawElement(OrientGraph graph, String className) {
        graph.createVertexClass(className);
        return new ODocument(className);
    }

    public Iterator<Vertex> vertices(final Direction direction, final String... labels) {
//        OrientGraphUtils.getEdgeClassNames(getGraph(), labels);
//        labels = (String[]) Stream.of(labels).map(OrientGraphUtils::encodeClassName).toArray();

        final ODocument doc = getRawDocument();

        final OMultiCollectionIterator<Vertex> iterable = new OMultiCollectionIterator<>();
        for (String fieldName : doc.fieldNames()) {
            final OPair<Direction, String> connection = getConnection(direction, fieldName, labels);
            if (connection == null)
                // SKIP THIS FIELD
                continue;

            final Object fieldValue = doc.field(fieldName);
            if (fieldValue != null)
                if (fieldValue instanceof OIdentifiable) {
                    addSingleVertex(doc, iterable, fieldName, connection, fieldValue, labels);

                } else if (fieldValue instanceof Collection<?>) {
                    Collection<?> coll = (Collection<?>) fieldValue;

                    if (coll.size() == 1) {
                        // SINGLE ITEM: AVOID CALLING ITERATOR
                        if (coll instanceof ORecordLazyMultiValue)
                            addSingleVertex(doc, iterable, fieldName, connection, ((ORecordLazyMultiValue) coll).rawIterator().next(), labels);
                        else if (coll instanceof List<?>)
                            addSingleVertex(doc, iterable, fieldName, connection, ((List<?>) coll).get(0), labels);
                        else
                            addSingleVertex(doc, iterable, fieldName, connection, coll.iterator().next(), labels);
                    } else {
                        // CREATE LAZY Iterable AGAINST COLLECTION FIELD
                        if (coll instanceof ORecordLazyMultiValue)
                            iterable.add(new OrientVertexIterator(this, coll, ((ORecordLazyMultiValue) coll).rawIterator(), connection, labels, ((ORecordLazyMultiValue) coll).size()));
                        else
                            iterable.add(new OrientVertexIterator(this, coll, coll.iterator(), connection, labels, -1));
                    }
                } else if (fieldValue instanceof ORidBag) {
                    iterable.add(new OrientVertexIterator(this, fieldValue, ((ORidBag) fieldValue).rawIterator(), connection, labels, -1));
                }
        }

        return iterable;


    }

    public <V> Iterator<VertexProperty<V>> properties(final String... propertyKeys) {
        Iterator<? extends Property<V>> properties = super.properties(propertyKeys);
        return StreamUtils.asStream(properties).map(p ->
            (VertexProperty<V>) new OrientVertexProperty<>( p.key(), p.value(), (OrientVertex) p.element())
        ).iterator();
    }

    @Override
    public <V> VertexProperty<V> property(final String key, final V value) {
        super.property(key, value);
        return new OrientVertexProperty<>(key, value, this);
    }

    @Override
    public <V> VertexProperty<V> property(String key, V value, Object... keyValues) {
        throw new NotImplementedException();
    }

    @Override
    public <V> VertexProperty<V> property(
            final VertexProperty.Cardinality cardinality,
            final String key,
            final V value,
            final Object... keyValues) {
        if(keyValues != null && keyValues.length > 0)
            throw new NotImplementedException();
        return this.property(key, value);
    }

    @Override
    public String toString() {
        String labelPart = "";

        if(!label().equals(OImmutableClass.VERTEX_CLASS_NAME))
            labelPart = "(" + label() + ")";
        return "v" + labelPart + "[" + id() + "]";
    }

    @Override
    public Edge addEdge(String label, Vertex inVertex, Object... keyValues) {
        if (inVertex == null)
            throw new IllegalArgumentException("destination vertex is null");
        
        checkArgument(!isNullOrEmpty(label), "label is invalid");

//        if (checkDeletedInTx())
//            throw new IllegalStateException("The vertex " + getIdentity() + " has been deleted");
//
//        if (inVertex.checkDeletedInTx())
//            throw new IllegalStateException("The vertex " + inVertex.getIdentity() + " has been deleted");
//
//        final OrientBaseGraph graph = setCurrentGraphInThreadLocal();
//        if (graph != null)
//            graph.autoStartTransaction();
//
        final ODocument outDocument = getRawDocument();
        if( !outDocument.getSchemaClass().isSubClassOf(OImmutableClass.VERTEX_CLASS_NAME) )
            throw new IllegalArgumentException("source record is not a vertex");

        final ODocument inDocument = ((OrientVertex)inVertex).getRawDocument();
        if( !inDocument.getSchemaClass().isSubClassOf(OImmutableClass.VERTEX_CLASS_NAME) )
            throw new IllegalArgumentException("destination record is not a vertex");

        final OrientEdge edge;

        label = OrientGraphUtils.encodeClassName(label);

        //TODO: add edge edgetype support
//        final OrientEdgeType edgeType = graph.getCreateEdgeType(label);
//        OVERWRITE CLASS NAME BECAUSE ATTRIBUTES ARE CASE SENSITIVE
//        label = edgeType.getName();
//
        final String outFieldName = getConnectionFieldName(Direction.OUT, label);
        final String inFieldName = getConnectionFieldName(Direction.IN, label);

//         since the label for the edge can potentially get re-assigned
//         before being pushed into the OrientEdge, the null check has to go here.
        if (label == null)
            throw new IllegalStateException("label cannot be null");

        // CREATE THE EDGE DOCUMENT TO STORE FIELDS TOO
        String className = label.equals(OImmutableClass.EDGE_CLASS_NAME) ?
            OImmutableClass.EDGE_CLASS_NAME : OImmutableClass.EDGE_CLASS_NAME + "_" + label;
        edge = new OrientEdge(graph, className, outDocument, inDocument, label);
        ElementHelper.attachProperties(edge, keyValues);

        //TODO: support inMemoryReferences
//        if (settings.isKeepInMemoryReferences())
//            edge.getRecord().fields(OrientBaseGraph.CONNECTION_OUT, rawElement.getIdentity(), OrientBaseGraph.CONNECTION_IN, inDocument.getIdentity());
//        else
        edge.getRawDocument().fields(OrientGraphUtils.CONNECTION_OUT, rawElement, OrientGraphUtils.CONNECTION_IN, inDocument);

        //TODO: support inMemoryReferences
//        if (settings.isKeepInMemoryReferences()) {
//            // USES REFERENCES INSTEAD OF DOCUMENTS
//            from = from.getIdentity();
//            to = to.getIdentity();
//        }

        createLink(outDocument, edge.getRawElement(), outFieldName);
        createLink(inDocument, edge.getRawElement(), inFieldName);

        //TODO: support clusters
//      edge.save(iClusterName);
        edge.save();
        inDocument.save();
        outDocument.save();
        return edge;
    }

    public String getConnectionFieldName(final Direction iDirection, final String iClassName) {
        if (iDirection == null || iDirection == Direction.BOTH)
            throw new IllegalArgumentException("Direction not valid");

        // TODO: removed support for nonVertexFieldsForEdgeLabels here
//        if (useVertexFieldsForEdgeLabels) {
            // PREFIX "out_" or "in_" TO THE FIELD NAME
            final String prefix = iDirection == Direction.OUT ? CONNECTION_OUT_PREFIX : CONNECTION_IN_PREFIX;
            if (iClassName == null || iClassName.isEmpty() || iClassName.equals(OImmutableClass.EDGE_CLASS_NAME))
                return prefix;

            return prefix + iClassName;
//        } else
            // "out" or "in"
//            return iDirection == Direction.OUT ? OrientGraphUtils.CONNECTION_OUT : OrientGraphUtils.CONNECTION_IN;
    }

    // this ugly code was copied from the TP2 implementation
    public Object createLink(final ODocument iFromVertex, final OIdentifiable iTo, final String iFieldName) {
        final Object out;
        OType outType = iFromVertex.fieldType(iFieldName);
        Object found = iFromVertex.field(iFieldName);

        final OClass linkClass = ODocumentInternal.getImmutableSchemaClass(iFromVertex);
        if (linkClass == null)
            throw new IllegalArgumentException("Class not found in source vertex: " + iFromVertex);

        final OProperty prop = linkClass.getProperty(iFieldName);
        final OType propType = prop != null && prop.getType() != OType.ANY ? prop.getType() : null;

        if (found == null) {
            //TODO: support these graph properties
//            if (iGgraph.isAutoScaleEdgeType() && (prop == null || propType == OType.LINK || "true".equalsIgnoreCase(prop.getCustom("ordered")))) {
//                // CREATE ONLY ONE LINK
//                out = iTo;
//                outType = OType.LINK;
//            } else if (propType == OType.LINKLIST || (prop != null && "true".equalsIgnoreCase(prop.getCustom("ordered")))) {
//                final Collection coll = new ORecordLazyList(iFromVertex);
//                coll.add(iTo);
//                out = coll;
//                outType = OType.LINKLIST;
           /* } else */ if (propType == null || propType == OType.LINKBAG) {
                final ORidBag bag = new ORidBag();
                bag.add(iTo);
                out = bag;
                outType = OType.LINKBAG;
            } else
                throw new IllegalStateException("Type of field provided in schema '" + prop.getType() + "' can not be used for link creation.");
//
        } else if (found instanceof OIdentifiable) {
            if (prop != null && propType == OType.LINK)
                throw new IllegalStateException("Type of field provided in schema '" + prop.getType() + "' can not be used for creation to hold several links.");
//
            if (prop != null && "true".equalsIgnoreCase(prop.getCustom("ordered"))) {
                final Collection coll = new ORecordLazyList(iFromVertex);
                coll.add(found);
                coll.add(iTo);
                out = coll;
                outType = OType.LINKLIST;
            } else {
                final ORidBag bag = new ORidBag();
                bag.add((OIdentifiable) found);
                bag.add(iTo);
                out = bag;
                outType = OType.LINKBAG;
            }
        } else if (found instanceof ORidBag) {
            // ADD THE LINK TO THE COLLECTION
            out = null;
            ((ORidBag) found).add(iTo);
        } else if (found instanceof Collection<?>) {
            // USE THE FOUND COLLECTION
            out = null;
            ((Collection<Object>) found).add(iTo);
        } else
            throw new IllegalStateException("Relationship content is invalid on field " + iFieldName + ". Found: " + found);

        if (out != null)
            // OVERWRITE IT
            iFromVertex.field(iFieldName, out, outType);

        return out;
    }

    public Iterator<Edge> edges(final Direction direction, String... edgeLabels) {
        final ODocument doc = getRawDocument();

//        edgeLabels = OrientGraphUtils.getEdgeClassNames(graph, edgeLabels);
//        edgeLabels = (String[]) Stream.of(edgeLabels).map(OrientGraphUtils::encodeClassName).toArray();

        final OMultiCollectionIterator<Edge> iterable = new OMultiCollectionIterator<Edge>().setEmbedded(true);
        for (String fieldName : doc.fieldNames()) {
            final OPair<Direction, String> connection = getConnection(direction, fieldName, edgeLabels);
            if (connection == null)
                // SKIP THIS FIELD
                continue;

            final Object fieldValue = doc.field(fieldName);
            if (fieldValue != null) {
                final OrientVertex iDestination = null;
                final OIdentifiable destinationVId = null;

                if (fieldValue instanceof OIdentifiable) {
                    addSingleEdge(doc, iterable, fieldName, connection, fieldValue, destinationVId, edgeLabels);

                } else if (fieldValue instanceof Collection<?>) {
                    Collection<?> coll = (Collection<?>) fieldValue;

                    if (coll.size() == 1) {
                        // SINGLE ITEM: AVOID CALLING ITERATOR
                        if (coll instanceof ORecordLazyMultiValue)
                            addSingleEdge(doc, iterable, fieldName, connection, ((ORecordLazyMultiValue) coll).rawIterator().next(), destinationVId, edgeLabels);
                        else if (coll instanceof List<?>)
                            addSingleEdge(doc, iterable, fieldName, connection, ((List<?>) coll).get(0), destinationVId, edgeLabels);
                        else
                            addSingleEdge(doc, iterable, fieldName, connection, coll.iterator().next(), destinationVId, edgeLabels);
                    } else {
                        // CREATE LAZY Iterable AGAINST COLLECTION FIELD
                        if (coll instanceof ORecordLazyMultiValue) {
                            iterable.add(new OrientEdgeIterator(this, iDestination, coll, ((ORecordLazyMultiValue) coll).rawIterator(), connection, edgeLabels, ((ORecordLazyMultiValue) coll).size()));
                        } else
                            iterable.add(new OrientEdgeIterator(this, iDestination, coll, coll.iterator(), connection, edgeLabels, -1));
                    }
                } else if (fieldValue instanceof ORidBag) {
                    iterable.add(new OrientEdgeIterator(this, iDestination, fieldValue, ((ORidBag) fieldValue).rawIterator(), connection, edgeLabels, ((ORidBag) fieldValue).size()));
                }
            }
        }
        return iterable;
    }

    /**
     * Determines if a field is a connections or not.
     *
     * @param iDirection  Direction to check
     * @param iFieldName  Field name
     * @param iClassNames Optional array of class names
     * @return The found direction if any
     */
    protected OPair<Direction, String> getConnection(final Direction iDirection, final String iFieldName, String... iClassNames) {
        if (iClassNames != null && iClassNames.length == 1 && iClassNames[0].equalsIgnoreCase("E"))
            // DEFAULT CLASS, TREAT IT AS NO CLASS/LABEL
            iClassNames = null;

        if (iDirection == Direction.OUT || iDirection == Direction.BOTH) {
            // FIELDS THAT STARTS WITH "out_"
            if (iFieldName.startsWith(CONNECTION_OUT_PREFIX)) {
                if (iClassNames == null || iClassNames.length == 0)
                    return new OPair<Direction, String>(Direction.OUT, getConnectionClass(Direction.OUT, iFieldName));

                // CHECK AGAINST ALL THE CLASS NAMES
                for (String clsName : iClassNames) {
                    clsName = OrientGraphUtils.encodeClassName(clsName);

                    if (iFieldName.equals(CONNECTION_OUT_PREFIX + clsName))
                        return new OPair<Direction, String>(Direction.OUT, clsName);

                    // GO DOWN THROUGH THE INHERITANCE TREE
                    OrientEdgeType type = graph.getEdgeType(clsName);
                    if (type != null) {
                        for (OClass subType : type.getAllSubclasses()) {
                            clsName = subType.getName();

                            if (iFieldName.equals(CONNECTION_OUT_PREFIX + clsName))
                                return new OPair<Direction, String>(Direction.OUT, clsName);
                        }
                    }
                }
            }
        }

        if (iDirection == Direction.IN || iDirection == Direction.BOTH) {
            // FIELDS THAT STARTS WITH "in_"
            if (iFieldName.startsWith(CONNECTION_IN_PREFIX)) {
                if (iClassNames == null || iClassNames.length == 0)
                    return new OPair<Direction, String>(Direction.IN, getConnectionClass(Direction.IN, iFieldName));

                // CHECK AGAINST ALL THE CLASS NAMES
                for (String clsName : iClassNames) {

                    if (iFieldName.equals(CONNECTION_IN_PREFIX + clsName))
                        return new OPair<Direction, String>(Direction.IN, clsName);

                    // GO DOWN THROUGH THE INHERITANCE TREE
                    OrientEdgeType type = graph.getEdgeType(clsName);
                    if (type != null) {
                        for (OClass subType : type.getAllSubclasses()) {
                            clsName = subType.getName();

                            if (iFieldName.equals(CONNECTION_IN_PREFIX + clsName))
                                return new OPair<Direction, String>(Direction.IN, clsName);
                        }
                    }
                }
            }
        }

        // NOT FOUND
        return null;
    }

    /**
     * Used to extract the class name from the vertex's field.
     *
     * @param iDirection Direction of connection
     * @param iFieldName Full field name
     * @return Class of the connection if any
     */
    public String getConnectionClass(final Direction iDirection, final String iFieldName) {
        if (iDirection == Direction.OUT) {
            if (iFieldName.length()>CONNECTION_OUT_PREFIX.length())
                return iFieldName.substring(CONNECTION_OUT_PREFIX.length());
        } else if (iDirection == Direction.IN) {
            if (iFieldName.length()>CONNECTION_IN_PREFIX.length())
                return iFieldName.substring(CONNECTION_IN_PREFIX.length());
        }
        return OrientEdgeType.CLASS_NAME;
    }

    protected void addSingleEdge(final ODocument doc, final OMultiCollectionIterator<Edge> iterable, String fieldName,
                                 final OPair<Direction, String> connection, final Object fieldValue,
                                 final OIdentifiable iTargetVertex, final String[] iLabels) {
        final OrientEdge toAdd = getEdge(graph, doc, fieldName, connection, fieldValue, iTargetVertex, iLabels);
        iterable.add(toAdd);
    }

    protected static OrientEdge getEdge(final OrientGraph graph, final ODocument doc, String fieldName,
                                        final OPair<Direction, String> connection, final Object fieldValue,
                                        final OIdentifiable iTargetVertex, final String[] iLabels) {
        final OrientEdge toAdd;

        final ODocument fieldRecord = ((OIdentifiable) fieldValue).getRecord();
        if (fieldRecord == null)
            return null;

        OImmutableClass immutableClass = ODocumentInternal.getImmutableSchemaClass(fieldRecord);
        if (immutableClass.isVertexType()) {
            if (iTargetVertex != null && !iTargetVertex.equals(fieldValue))
                return null;

            // DIRECT VERTEX, CREATE A DUMMY EDGE BETWEEN VERTICES
            if (connection.getKey() == Direction.OUT)
                toAdd = new OrientEdge(graph, doc, fieldRecord, connection.getValue());
            else
                toAdd = new OrientEdge(graph, fieldRecord, doc, connection.getValue());

        } else if (immutableClass.isEdgeType()) {
            // EDGE
            if (iTargetVertex != null) {
                Object targetVertex = OrientEdge.getConnection(fieldRecord, connection.getKey().opposite());

                if (!iTargetVertex.equals(targetVertex))
                    return null;
            }

            toAdd = new OrientEdge(graph, fieldRecord);
        } else
            throw new IllegalStateException("Invalid content found in " + fieldName + " field: " + fieldRecord);

        return toAdd;
    }


    private void addSingleVertex(final ODocument doc, final OMultiCollectionIterator<Vertex> iterable, String fieldName,
                                 final OPair<Direction, String> connection, final Object fieldValue, final String[] iLabels) {
        final OrientVertex toAdd;

        final ODocument fieldRecord = ((OIdentifiable) fieldValue).getRecord();
        OImmutableClass immutableClass = ODocumentInternal.getImmutableSchemaClass(fieldRecord);
        if (immutableClass.isVertexType()) {
            // DIRECT VERTEX
            toAdd = new OrientVertex(graph, fieldRecord);
        } else if (immutableClass.isEdgeType()) {
            // EDGE
//            if (settings.isUseVertexFieldsForEdgeLabels() || OrientEdge.isLabeled(OrientEdge.getRecordLabel(fieldRecord), iLabels)) {
                final OIdentifiable vertexDoc = OrientEdge.getConnection(fieldRecord, connection.getKey().opposite());
                if (vertexDoc == null) {
                    fieldRecord.reload();
                    if (vertexDoc == null) {
                        OLogManager.instance().warn(this, "Cannot load edge " + fieldRecord + " to get the " + connection.getKey().opposite() + " vertex");
                        return;
                    }
                }

                toAdd = new OrientVertex(graph, vertexDoc);
//            } else
//                toAdd = null;
        } else
            throw new IllegalStateException("Invalid content found in " + fieldName + " field: " + fieldRecord);

        if (toAdd != null)
            // ADD THE VERTEX
            iterable.add(toAdd);
    }


}
