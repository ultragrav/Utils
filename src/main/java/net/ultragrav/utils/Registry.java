package net.ultragrav.utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class Registry<I, T> {
    private final Map<I, T> identifierToObject = new ConcurrentHashMap<>();
    private final Map<Integer, T> idToObject = new ConcurrentHashMap<>();
    private final Map<T, Integer> objectToId = new ConcurrentHashMap<>();
    private final Map<T, I> objectToIdentifier = new ConcurrentHashMap<>();
    private ReentrantLock lock = new ReentrantLock();

    private final AtomicInteger idCounter = new AtomicInteger();

    private Registry<I, ? super T> parent = null;
    private Function<I, I> identifierTransformation = null;

    /**
     * Creates a child registry, which is a registry that is a child of this registry.
     *
     * @return the child registry
     */
    public <L extends T> Registry<I, L> createChild(Function<I, I> identifierTransformation) {
        Registry<I, L> registry = new Registry<>();
        registry.parent = this;
        registry.identifierTransformation = identifierTransformation;
        registry.lock = this.lock;
        return registry;
    }

    /**
     * Registers the object in the registry, with the given identifier.
     *
     * @param identifier the identifier
     * @param object     the object
     * @return the object
     */
    public <B extends T> B register(I identifier, B object, int id) {
        if (identifier == null || object == null) {
            throw new IllegalArgumentException("Identifier or object cannot be null!");
        }
        lock.lock();
        try {

            if (idToObject.containsKey(id)) {
                throw new IllegalArgumentException("ID " + id + " is already taken!");
            }

            identifierToObject.put(identifier, object);
            idToObject.put(id, object);
            objectToId.put(object, id);
            objectToIdentifier.put(object, identifier);

            if (parent != null) {
                parent.register(identifierTransformation.apply(identifier), object);
            }
        } finally {
            lock.unlock();
        }
        return object;
    }

    /**
     * Registers the object in the registry, with a new identifier.
     *
     * @param object the object
     * @return the object
     */
    public <B extends T> B register(I identifier, B object) {
        lock.lock();
        try {
            if (get(identifier) != null) {
                unregister(identifier);
            }
            int id;
            while (idToObject.containsKey(id = idCounter.getAndIncrement())) ;
            register(identifier, object, id);
        } finally {
            lock.unlock();
        }
        return object;
    }

    /**
     * Gets the object with the given identifier.
     *
     * @param identifier the identifier
     * @return the object
     */
    public T get(I identifier) {
        lock.lock();
        try {
            if (identifier == null) return null;
            return identifierToObject.get(identifier);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the object with the given id.
     *
     * @param id the id of the object to return
     * @return the object with the given id
     */
    public T get(int id) {
        lock.lock();
        try {
            return idToObject.get(id);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the identifier for the given object.
     *
     * @param object the object to return the identifier for
     * @return the identifier for the given object
     */
    public I getIdentifier(T object) {
        if (object == null) return null;
        lock.lock();
        try {
            return objectToIdentifier.get(object);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the id for the given identifier.
     *
     * @param identifier the identifier to return the id for
     * @return the id for the given identifier
     */
    public int getIdByIdentifier(I identifier) {
        lock.lock();
        try {
            return objectToId.get(identifierToObject.get(identifier));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the id of the given object.
     *
     * @param object the object to return the id for
     * @return the id of the given object
     */
    public int getId(T object) {
        lock.lock();
        try {
            return objectToId.get(object);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Assigns the given identifiers to the given objects.
     *
     * @param identifierToId a mapping from identifiers to ids
     */
    public void assumeIds(Map<I, Integer> identifierToId) {
        lock.lock();
        try {

            // Create a copy of the object to id map.
            Map<T, Integer> objectToIdCopy = new HashMap<>(objectToId);

            // Create a list of objects to reassign.
            List<T> reAdd = new ArrayList<>();

            // Loop.
            for (Map.Entry<I, Integer> entry : identifierToId.entrySet()) {
                T o = get(entry.getKey());
                if (o == null) continue;

//                System.out.println();

                // We have that object.

                // Remove the current id of the object from id to object map.
                if (idToObject.get(objectToIdCopy.get(o)) == o) { // Only if it is still mapped to itself.
                    idToObject.remove(objectToIdCopy.get(o));
                }

                // Set the object's new id.
                objectToId.put(o, entry.getValue());

                // Replace the id in the id to object map.
                T prev = idToObject.put(entry.getValue(), o);
                if (prev != null && prev != o) {
                    reAdd.add(prev);
                }

                // Remove it because we have put it back in.
                reAdd.remove(o);
            }

            // Set idCounter to the highest id+1 in the map.
            idCounter.set(Collections.max(idToObject.keySet()) + 1);

            for (T o : reAdd) {
                int id;
                while (idToObject.containsKey(id = idCounter.getAndIncrement())) ;
                idToObject.put(id, o);
                objectToId.put(o, id);
            }

        } finally {
            lock.unlock();
        }
    }

    public <K, V> Map<K, V> createMultiKeyMap(BiFunction<I, T, K[]> keyGen, BiFunction<I, T, V> valGen) {
        Map<K, V> ret = new HashMap<>();
        for (Map.Entry<I, T> ent : identifierToObject.entrySet()) {
            for (K key : keyGen.apply(ent.getKey(), ent.getValue())) {
                ret.put(key, valGen.apply(ent.getKey(), ent.getValue()));
            }
        }
        return ret;
    }

    public <K, V> Map<K, V> createMap(BiFunction<I, T, K> keyGen, BiFunction<I, T, V> valGen) {
        Map<K, V> ret = new HashMap<>();
        for (Map.Entry<I, T> ent : identifierToObject.entrySet()) {
            ret.put(keyGen.apply(ent.getKey(), ent.getValue()), valGen.apply(ent.getKey(), ent.getValue()));
        }
        return ret;
    }

    /**
     * Returns a collection of all objects.
     *
     * @return a collection of all objects
     */
    public Collection<T> values() {
        return Collections.unmodifiableCollection(idToObject.values());
    }

    /**
     * Removes all objects from the object manager.
     */
    public void clear() {
        lock.lock();
        idCounter.set(0);
        idToObject.clear();
        objectToId.clear();
        identifierToObject.keySet().forEach(it -> {
            if (parent != null) {
                parent.unregister(identifierTransformation.apply(it));
            }
        });
        identifierToObject.clear();
        objectToIdentifier.clear();
        lock.unlock();
    }

    /**
     * Returns a map of all identifiers to objects.
     *
     * @return a map of all identifiers to objects
     */
    public Map<I, T> asMap() {
        return new HashMap<>(identifierToObject);
    }

    /**
     * Returns a map of all ids to identifiers.
     *
     * @return a map of all ids to identifiers
     */
    public Map<Integer, I> getPalette() {
        lock.lock();
        try {
            Map<Integer, I> palette = new HashMap<>();
            for (Map.Entry<Integer, T> entry : idToObject.entrySet()) {
                palette.put(entry.getKey(), objectToIdentifier.get(entry.getValue()));
            }
            return palette;
        } finally {
            lock.unlock();
        }
    }

    public T unregister(I identifier) {
        lock.lock();
        try {
            T object = identifierToObject.remove(identifier);
            if (object != null) {
                idToObject.remove(objectToId.remove(object));
                objectToIdentifier.remove(object);
            }
            if (parent != null) {
                parent.unregister(identifierTransformation.apply(identifier));
            }
            return object;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return identifierToObject.isEmpty();
    }

    public void forEach(Consumer<Map.Entry<I, T>> consumer) {
        identifierToObject.entrySet().forEach(consumer);
    }
}
