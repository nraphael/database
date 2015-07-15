/*

Copyright (C) SYSTAP, LLC 2006-2015.  All rights reserved.

Contact:
     SYSTAP, LLC
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@systap.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
/*
 * Created on Sep 3, 2008
 */

package com.bigdata.striterator;

import java.io.Serializable;
import java.util.Arrays;
import java.util.UUID;

import com.bigdata.bop.solutions.JVMDistinctBindingSetsOp;
import com.bigdata.btree.BTree;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.btree.NOPTupleSerializer;
import com.bigdata.btree.keys.ASCIIKeyBuilderFactory;
import com.bigdata.btree.keys.KVO;
import com.bigdata.journal.IIndexManager;
import com.bigdata.journal.TemporaryStore;
import com.bigdata.relation.rule.IRule;
import com.bigdata.relation.rule.eval.ISolution;
import com.bigdata.util.BytesUtil;

/**
 * A filter that imposes a DISTINCT constraint on the {@link ISolution}s
 * generated by an {@link IRule}. The filter is optimized if only a single
 * chunk is visited by the source iterator. Otherwise, the filter is implemented
 * using a {@link BTree} backed by a {@link TemporaryStore}.
 * <p>
 * When more than one chunk is processed, {@link ISolution}s are transformed
 * into unsigned byte[] keys. The {@link BTree} is tested for each such key. If
 * the key is NOT found, then it is inserted into the {@link BTree} and the
 * solution is passed by the filter. Otherwise the solution is rejected by the
 * filter. The backing {@link BTree} is closed when the filter is finalized, but
 * it will hold a hard reference to the {@link TemporaryStore} until then.
 * Solutions are processed in chunks for efficient ordered reads and writes on
 * the {@link BTree}.
 * 
 * @todo A statistical distinct filter can be implemented using bloom filter
 *       INSTEAD of a {@link BTree} but the bloom filter parameters MUST be
 *       chosen so as to make the possibility of a false positive sufficiently
 *       unlikely to satisfy the application criteria. However, such a filter
 *       will always have a non-zero chance of incorrectly rejecting a solution
 *       when that solution has NOT been seen by the filter. Since the bloom
 *       filter can under-generate, it could only be applied in very specialized
 *       circumstances, e.g., it might be OK for text search.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @deprecated by {@link JVMDistinctBindingSetsOp}
 */
abstract public class DistinctFilter<E> implements IChunkConverter<E, E> {

    /**
     * Used to lazily obtain a {@link TemporaryStore}.
     * <p>
     * Note: NOT {@link Serializable}.
     */
    private final IIndexManager indexManager;

    /**
     * Lazily created if more than one chunk will be visited.
     * <p>
     * Note: NOT {@link Serializable}.
     */
    private BTree btree = null;

    /**
     * 
     * @param indexManager
     *            Used to lazily obtain a {@link TemporaryStore}.
     */
    public DistinctFilter(final IIndexManager indexManager) {

        if (indexManager == null)
            throw new IllegalArgumentException();

        this.indexManager = indexManager;

    }

    public E[] convert(final IChunkedOrderedIterator<E> src) {
        
        if (src == null)
            throw new IllegalArgumentException();
        
        // read a chunk from the source iterator.
        final E[] chunk = src.nextChunk();

        // true iff there is nothing more available from the source itr.
        final boolean exhausted = !src.hasNext();

        final int n = chunk.length;
        
        final KVO<E>[] a = new KVO[n]; 
        
        for (int i = 0; i < n; i++) {
            
            final E e = chunk[i];
            
            a[i] = new KVO(getSortKey(e), null/*val*/, e);
            
        }
        
        // Put into sorted order by the generated sort keys.
        Arrays.sort(a);
        
        if (btree == null && exhausted) {
            
            /*
             * Special case when we have not yet created a BTree to hold the
             * distinct keys and the source iterator will not visit any more
             * elements. For this case we can just compare each element in
             * sorted order with the next element in sorted order and drop
             * duplicates.
             */

            int j = 0;

            // chunk large enough if everything is distinct.
            final E[] tmp = (E[]) java.lang.reflect.Array.newInstance(
//                    chunk[0].getClass(),
                    chunk.getClass().getComponentType(),
                    n);

            // always emit the first element.
            tmp[j++] = a[0].obj;

            for (int i = 1; i < n; i++) {

                if (!BytesUtil.bytesEqual(a[i - 1].key, a[i].key)) {

                    tmp[j++] = a[i].obj;

                }
                
            }

            if (j != n) {

                // make it dense.

                E[] tmp2 = (E[]) java.lang.reflect.Array.newInstance(//
//                        tmp[0].getClass(),
                        tmp.getClass().getComponentType(),
                        j);

                System.arraycopy(tmp, 0, tmp2, 0, j);

                return tmp2;

            }

            return tmp;
            
        }
        
        /*
         * General case.
         */
        
        if (btree == null) {
            
            /*
             * Create the B+Tree on which we will write the distinct keys.
             */
            
            final IndexMetadata metadata = new IndexMetadata(UUID.randomUUID());

            /*
             * The key builder is dead simple since keys are byte[]s and values
             * are not used.
             */

            metadata.setTupleSerializer(new NOPTupleSerializer(
                    new ASCIIKeyBuilderFactory(0/* initialCapacity */)));
            
            // create the B+Tree.
            btree = BTree.create(indexManager.getTempStore(), metadata);
            
        }

        /*
         * a[] is in sorted order by the unsigned byte[] keys. For each key, we
         * test the B+Tree. If the key is NOT found then we insert the key and
         * add the element to the output chunk. If the key is found then we skip
         * it since it has already been observed (and by extension, the element
         * paired with that key has already been observed).
         */
        {
        
            int j = 0;

            // chunk large enough if everything is distinct.
            final E[] tmp = (E[]) java.lang.reflect.Array.newInstance(
//                    chunk[0].getClass(),
                    chunk.getClass().getComponentType(),//
                    n);

            for (int i = 0; i < n; i++) {

                if (!btree.contains(a[i].key)) {

                    btree.insert(a[i].key, null/* val */);
                    
                    tmp[j++] = a[i].obj;
                    
                }
                
            }

            if (j != n) {

                // make it dense.

                E[] tmp2 = (E[]) java.lang.reflect.Array.newInstance(
//                        chunk[0].getClass(),
                        chunk.getClass().getComponentType(),//
                        j);

                System.arraycopy(tmp, 0, tmp2, 0, j);

                return tmp2;

            }

            return tmp;

        }
        
    }

    /**
     * Return an unsigned byte[] key that is a representation of the visited
     * element. Elements are judged for distinctness in terms of the generated
     * sort key.
     * 
     * @param e
     *            The visited element.
     * 
     * @return The unsigned byte[] key.
     */
    abstract protected byte[] getSortKey(E e);

}
