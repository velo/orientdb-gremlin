package org.apache.tinkerpop.gremlin.orientdb;

import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ORecordElement;
import com.orientechnologies.orient.core.record.impl.ODocument;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;


public class OrientElement implements Element {
    protected OIdentifiable rawElement;
    protected OrientGraph graph;

    public OrientElement(final OrientGraph graph, final OIdentifiable rawElement) {
        if (rawElement == null)
            throw new IllegalArgumentException("rawElement must not be null!");
        this.graph = checkNotNull(graph);
        this.rawElement = checkNotNull(rawElement);
    }

    public Object id() {
        return rawElement.getIdentity();
    }

    public String label() {
        String internalClassName = getRawDocument().getClassName();
        // User labels on edges/vertices are prepended with E_ or V_ . The user should not see that.
        return internalClassName.length() == 1 ? internalClassName : internalClassName.substring(2);
    }

    public Graph graph() {
        return graph;
    }

    public <V> Property<V> property(final String key, final V value) {
        ODocument doc = getRawDocument();
        doc.field(key, value);
        doc.save();
        return new OrientProperty<>(key, value, this);
    }

    public void remove() {
        ODocument doc = getRawDocument();
        if (doc.getInternalStatus() == ORecordElement.STATUS.NOT_LOADED) {
            doc.load();
        }
        doc.delete();
    }

    public <V> Iterator<? extends Property<V>> properties(final String... propertyKeys) {
        ODocument record = rawElement.getRecord();
        if (record == null)
            record = new ODocument();

        Map<String, Object> properties = record.toMap();
        HashSet<String> keys = new HashSet<>(Arrays.asList(propertyKeys));

        Stream<Map.Entry<String, Object>> entries = StreamUtils.asStream(properties.entrySet().iterator());
        if (keys.size() > 0) {
            entries = entries.filter(entry -> keys.contains(entry.getKey()));
        }

        Stream<OrientProperty<V>> propertyStream = entries.map(entry -> new OrientProperty<>(entry.getKey(), (V) entry.getValue(), this));
        return propertyStream.iterator();
    }

    public void save() {
        ((ODocument)rawElement).save();
    }

    public ODocument getRawDocument() {
        if (!(rawElement instanceof ODocument))
            rawElement = new ODocument(rawElement.getIdentity());
        return (ODocument) rawElement;
    }

    public OrientGraph getGraph() {
        return graph;
    }

    public OIdentifiable getRawElement() {
        return rawElement;
    }

    @Override
    public int hashCode()
    {
      final int prime = 73;
      int result = 1;
      result = prime * result + ((rawElement == null) ? 0 : rawElement.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj)
    {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      OrientElement other = (OrientElement) obj;
      if (rawElement == null)
      {
        if (other.rawElement != null)
          return false;
      }
      else if (!rawElement.equals(other.rawElement))
        return false;
      return true;
    }

}
