/*
 * Javolution - Java(TM) Solution for Real-Time and Embedded Systems
 * Copyright (C) 2012 - Javolution (http://javolution.org/)
 * All rights reserved.
 * 
 * Permission to use, copy, modify, and distribute this software is
 * freely granted, provided that this notice is preserved.
 */
package javolution.util;

import javolution.util.SparseArray.EntryNode;
import javolution.util.SparseArray.Node;
import javolution.util.SparseArray.NullNode;
import javolution.util.function.Equality;
import javolution.util.function.Order;
import javolution.util.internal.map.SortedMapImpl;

/**
 * <p> A <a href="http://en.wikipedia.org/wiki/Trie">trie-based</a> map 
 *     allowing for quick searches, insertions and deletion.</p> 
 *     
 * <p> Worst-case execution time when adding new entries is 
 *     significantly better than when using standard hash table since there is
 *     no resize/rehash ever performed.</p> 
 *   
 * <p> Sparse maps are efficient for indexing multi-dimensional information 
 *     such as dictionaries, multi-keys attributes, geographical coordinates,
 *     sparse matrix elements, etc.
 * <pre>{@code
 * // Prefix Maps.
 * SparseMap<String, President> presidents = new SparseMap<>(Order.LEXICAL);
 * presidents.put("John Adams", johnAdams);
 * presidents.put("John Tyler", johnTyler);
 * presidents.put("John Kennedy", johnKennedy);
 * ...
 * presidents.subMap("J", "K").clear(); // Removes all president whose first name starts with "J" ! 
 * 
 * // Multi-Keys Attributes.
 * Indexer<Person> indexer = person -> (person.isMale() ? 0 : 128) + person.age();
 * FastMap<Person, Integer> population = new FastMap<>(indexer); // Population count by gender/age.
 * ...
 * int numberOfBoys = population.subMap(new Person(MALE, 0), new Person(MALE, 19)).values().sum();
 * 
 * // Spatial indexing (R-Tree).
 * class LatLong implements Binary<Latitude, Longitude> {...}
 * Indexer<LatLong> mortonCode = latLong -> MathLib.interleave(latLong.lat(DEGREE) + 90, latLong.long(DEGREE) + 180);
 * SparseMap<LatLong, City> cities = new SparseMap<>(mortonCode); // Keep space locality.
 * cities.put(parisLatLong, paris);
 * cities.put(londonLatLong, london);
 * ...
 * cities.subMap(EUROPE_LAT_LONG_MIN, EUROPE_LAT_LONG_MAX).values().filter(inEurope).forEach(...); // Iterates over European cities.
 * Comparator<City> distanceToParisComparator = ...; // city1 < city2 if city1 is closer to Paris than city2
 * City nearestParis = cities.subMap(aroundParisMin, aroundParisMax).values()
 *     .filter(notParis) // Avoid returning Paris! 
 *     .comparator(distanceToParisComparator)
 *     .min();
 *     
 * // Sparse Matrix.
 * class RowColumn extends Binary<Index, Index> { ... }
 * SparseMap<RowColumn, E> sparseMatrix = new SparseMap<>(Order.QUADTREE); 
 * sparseMatrix.put(RowColumn.of(2, 44), e);
 * ...
 * // Iterates only over the diagonal entries of the sparse matrix.
 * Consumer<Entry<RowColumn, E>> consumer = ...; 
 * forEachOnDiagonal(consumer, sparseMatrix, 0, 1024); // Assuming 1024 is the maximum row/column dimension (and a power of 2).
 * ...
 * static void forEachOnDiagonal(Consumer<Entry<RowColumn, E>> consumer, FastMap<RowColumn, E> sparseMatrix, int i, int length) {
 *     if (sparseMatrix.isEmpty()) return; 
 *     if (length == 1) {
 *          consumer.accept(sparseMatrix.getEntry(RowColumn.of(i,i)));  
 *     } else { // The quadtree order allows for perfect binary split!
 *          int half = length >> 1; 
 *          RowColumn start = RowColumn.of(i,i);
 *          RowColumn middle = RowColumn.of(i+half,i+half); 
 *          RowColumn end = RowColumn.of(i+length,i+length);
 *          forEachOnDiagonal(sparseMatrix.subMap(start, middle), i, half);
 *          forEachOnDiagonal(sparseMatrix.subMap(middle, end), i+half, length-half);
 *     }
 * }}</pre></p>
 * 
 * <p> The memory footprint of the sparse map is automatically adjusted up or 
 *     down based on the map size (minimal when the map is cleared).</p>
 *      
 * @author <a href="mailto:jean-marie@dautelle.com">Jean-Marie Dautelle</a>
 * @version 7.0, September 13, 2015
 */
public class SparseMap<K,V> extends FastMap<K,V> {
	
	private static final long serialVersionUID = 0x700L; // Version. 
	private static final Object SUB_MAP = new Object();
	private final Order<? super K> order; 
	private Node<K,V> root = NullNode.getInstance(); // Value is either V or FastMap<K,V>
	private int size;
	
	/**
     * Creates an empty map using an arbitrary order (hash based).
     */
    public SparseMap() {
    	this(Order.DEFAULT);
    }
    
	/**
     * Creates an empty map using the specified order.
     * 
     * @param order the ordering of the map.
     */
    public SparseMap(Order<? super K> order) {
    	this.order = order;
    }
        
	@Override
	public int size() {
		return size;
	}
	
	@Override
	public Order<? super K> order() {
		return order;
	}
	
	@Override
	public Equality<? super V> valuesEquality() {
		return Equality.DEFAULT;
	}
	
	@Override
	public SparseMap<K, V> clone() {
		SparseMap<K,V> copy = (SparseMap<K,V>) super.clone();
		copy.root = root.clone();
		return copy;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> getEntry(K key) {
		EntryNode<K,V> entry = root.getEntry(order.indexOf(key));
        if (entry == null) return null;
        if (entry.key == SUB_MAP)    
             return ((FastMap<K,V>)entry.value).getEntry(key); 
        return order.areEqual(entry.getKey(), key) ? entry : null;
    }		

	@SuppressWarnings("unchecked")
	@Override
	public V put(K key, V value) {
		int i = order.indexOf(key);
		EntryNode<K,V> entry = root.entry(i);
		if (entry.key == SUB_MAP) {
			FastMap<K,V> subMap = (FastMap<K,V>)entry.value;
			int previousSize = subMap.size();
			V previousValue = subMap.put(key, value);
			if (subMap.size() > previousSize) size++;
			return previousValue;			
		}
		if (entry == SparseArray.UPSIZE) { // Resizes.
			root = root.upsize(i);
			entry = root.entry(i);
		}		
		if (entry.key == EntryNode.NOT_INITIALIZED) { // New entry.
			entry.key = key;
			entry.value = value;
			size++;
			return null;
		} 
		// Existing entry.
		if (order.areEqual(entry.key, key))
			return entry.setValue(value);
		// Collision.
        Order<? super K> subOrder = order.subOrder(key);
        FastMap<K,V> subMap = (subOrder != null) ? 
		         new SparseMap<K,V>(subOrder) : 
		        	 new SortedMapImpl<K,V>(order);
	    subMap.put(entry.key, entry.value);
	    entry.key = (K) SUB_MAP; // Cast has no effect.
	    entry.value = (V) subMap; // Cast has no effect.
	    size++;
	    return subMap.put(key, value);
    }
	
	@SuppressWarnings("unchecked")
	@Override
	public Entry<K,V> removeEntry(K key) {
		int i = order.indexOf((K)key);
		EntryNode<K,V> entry = root.getEntry(i);
        if (entry == null) return null;
        if (entry.key == SUB_MAP) {
        	Entry<K,V> previousEntry = ((FastMap<K,V>)entry.value).removeEntry(key);
        	if (previousEntry != null) size--;
        	return previousEntry;
        }
        if (!order.areEqual(entry.getKey(), key)) return null;
        Object tmp = root.removeEntry(i);
        if (tmp == SparseArray.DOWNSIZE) {
        	root = root.downsize(i);
        } else if (tmp == SparseArray.DELETE) {
        	root = NullNode.getInstance();
        }
        size--;
        return (Entry<K, V>) entry;
	}
	
	@Override
	public void clear() {
		root = NullNode.getInstance();
		size = 0;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> firstEntry() {
		EntryNode<K,V> entry = root.ceilingEntry(0);
		if ((entry != null) && (entry.key == SUB_MAP))
			return ((FastMap<K,V>)entry.value).firstEntry();
		return entry;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> lastEntry() {
		EntryNode<K,V> entry = root.floorEntry(-1);
		if ((entry != null) && (entry.key == SUB_MAP))
			return ((FastMap<K,V>)entry.value).lastEntry();
		return entry;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> higherEntry(K key) {
		int i = order.indexOf(key);
		EntryNode<K,V> entry = root.ceilingEntry(i);
		if (entry == null) return null;
		if (entry.key == SUB_MAP) {
			Entry<K,V> subMapEntry = ((FastMap<K,V>)entry.value).higherEntry(key);
			if (subMapEntry != null) return subMapEntry;
		} else {
			if (order.compare(entry.key, key) > 0) return entry;
		}
		if (entry.getIndex() == -1) return null;
		entry = root.ceilingEntry(i+1);
		if ((entry != null) && (entry.key == SUB_MAP)) 
			return ((FastMap<K,V>)entry.value).firstEntry();
		return entry;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> lowerEntry(K key) {
		int i = order.indexOf(key);
		EntryNode<K,V> entry = root.floorEntry(i);
		if (entry == null) return null;
		if (entry.key == SUB_MAP) {
			Entry<K,V> subMapEntry = ((FastMap<K,V>)entry.value).lowerEntry(key);
			if (subMapEntry != null) return subMapEntry;
		} else {
			if (order.compare(entry.key, key) < 0) return entry;
		}
		if (entry.getIndex() == 0) return null;
		entry = root.floorEntry(i-1);
		if ((entry != null) && (entry.key == SUB_MAP)) 
			return ((FastMap<K,V>)entry.value).lastEntry();
		return entry;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> ceilingEntry(K key) {
		int i = order.indexOf(key);
		EntryNode<K,V> entry = root.ceilingEntry(i);
		if (entry == null) return null;
		if (entry.key == SUB_MAP) {
			Entry<K,V> subMapEntry = ((FastMap<K,V>)entry.value).ceilingEntry(key);
			if (subMapEntry != null) return subMapEntry;
		} else {
			if (order.compare(entry.key, key) >= 0) return entry;
		}
		if (entry.getIndex() == -1) return null;
		entry = root.ceilingEntry(i+1);
		if ((entry != null) && (entry.key == SUB_MAP)) 
			return ((FastMap<K,V>)entry.value).firstEntry();
		return entry;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Entry<K, V> floorEntry(K key) {
		int i = order.indexOf(key);
		EntryNode<K,V> entry = root.floorEntry(i);
		if (entry == null) return null;
		if (entry.key == SUB_MAP) {
			Entry<K,V> subMapEntry = ((FastMap<K,V>)entry.value).floorEntry(key);
			if (subMapEntry != null) return subMapEntry;
		} else {
			if (order.compare(entry.key, key) <= 0) return entry;
		}
		if (entry.getIndex() == 0) return null;
		entry = root.floorEntry(i-1);
		if ((entry != null) && (entry.key == SUB_MAP)) 
			return ((FastMap<K,V>)entry.value).lastEntry();
		return entry;
	}

}