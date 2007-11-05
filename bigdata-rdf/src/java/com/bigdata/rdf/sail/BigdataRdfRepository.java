/**

The Notice below must appear in each file of the Source Code of any
copy you distribute of the Licensed Product.  Contributors to any
Modifications may add their own copyright notices to identify their
own contributions.

License:

The contents of this file are subject to the CognitiveWeb Open Source
License Version 1.1 (the License).  You may not copy or use this file,
in either source code or executable form, except in compliance with
the License.  You may obtain a copy of the License from

  http://www.CognitiveWeb.org/legal/license/

Software distributed under the License is distributed on an AS IS
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See
the License for the specific language governing rights and limitations
under the License.

Copyrights:

Portions created by or assigned to CognitiveWeb are Copyright
(c) 2003-2003 CognitiveWeb.  All Rights Reserved.  Contact
information for CognitiveWeb is available at

  http://www.CognitiveWeb.org

Portions Copyright (c) 2002-2003 Bryan Thompson.

Acknowledgements:

Special thanks to the developers of the Jabber Open Source License 1.0
(JOSL), from which this License was derived.  This License contains
terms that differ from JOSL.

Special thanks to the CognitiveWeb Open Source Contributors for their
suggestions and support of the Cognitive Web.

Modifications:

*/
/*
 * Created on Apr 12, 2007
 */

package com.bigdata.rdf.sail;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import org.CognitiveWeb.util.PropertyUtil;
import org.apache.log4j.Logger;
import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.sesame.sail.NamespaceIterator;
import org.openrdf.sesame.sail.RdfRepository;
import org.openrdf.sesame.sail.SailChangedEvent;
import org.openrdf.sesame.sail.SailChangedListener;
import org.openrdf.sesame.sail.SailInitializationException;
import org.openrdf.sesame.sail.SailInternalException;
import org.openrdf.sesame.sail.SailUpdateException;
import org.openrdf.sesame.sail.StatementIterator;
import org.openrdf.sesame.sail.query.BooleanExpr;
import org.openrdf.sesame.sail.query.DirectSubClassOf;
import org.openrdf.sesame.sail.query.DirectSubPropertyOf;
import org.openrdf.sesame.sail.query.DirectType;
import org.openrdf.sesame.sail.query.GraphPattern;
import org.openrdf.sesame.sail.query.GraphPatternQuery;
import org.openrdf.sesame.sail.query.PathExpression;
import org.openrdf.sesame.sail.query.Query;
import org.openrdf.sesame.sail.query.SetOperator;
import org.openrdf.sesame.sail.query.TriplePattern;
import org.openrdf.sesame.sail.query.ValueCompare;
import org.openrdf.sesame.sail.query.ValueExpr;
import org.openrdf.sesame.sail.query.Var;
import org.openrdf.sesame.sail.util.EmptyStatementIterator;
import org.openrdf.sesame.sail.util.SailChangedEventImpl;

import com.bigdata.rdf.inf.BackchainTypeResourceIterator;
import com.bigdata.rdf.inf.InferenceEngine;
import com.bigdata.rdf.model.OptimizedValueFactory;
import com.bigdata.rdf.model.StatementEnum;
import com.bigdata.rdf.model.OptimizedValueFactory._Value;
import com.bigdata.rdf.rio.IStatementBuffer;
import com.bigdata.rdf.rio.StatementBuffer;
import com.bigdata.rdf.spo.ISPOFilter;
import com.bigdata.rdf.spo.ISPOIterator;
import com.bigdata.rdf.spo.SPO;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.IAccessPath;
import com.bigdata.rdf.store.IRawTripleStore;
import com.bigdata.rdf.store.ITripleStore;
import com.bigdata.rdf.store.LocalTripleStore;
import com.bigdata.rdf.store.StatementWithType;
import com.bigdata.rdf.store.TMStatementBuffer;
import com.bigdata.rdf.store.AbstractTripleStore.EmptyAccessPath;

/**
 * A Sesame 1.x SAIL integration.
 * <p>
 * Note: Sesame 1.x coupled the control logic and data structures in such a way
 * that you could not write your own JOIN operators, which makes it very
 * difficult to optimize performance.
 * <p>
 * Note: Only a simple transaction model is supported. There is no transactional
 * isolation. Queries run against the "live" indices and therefore CAN NOT be
 * used concurrently or concurrently with writes on the store.
 * <p>
 * <em>THIS CLASS IS NOT THREAD SAFE</em>
 * 
 * @todo Queries could run concurrently against the last committed state of the
 *       store. This would require an {@link RdfRepository} wrapper that was
 *       initialized from the last commit record on the store and then was used
 *       to execute queries. In turn, that should probably be built over a
 *       read-only {@link ITripleStore} reading from the last commit record on
 *       the store at the time that the view is created.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class BigdataRdfRepository implements RdfRepository {

    /**
     * Logger.
     */
    public static final Logger log = Logger
            .getLogger(BigdataRdfRepository.class);

    /**
     * Additional parameters understood by the Sesame 1.x SAIL implementation.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class Options extends com.bigdata.journal.Options {

        /**
         * This optional boolean property may be used to specify whether or not
         * RDFS entailments are maintained by eager closure of the knowledge
         * base (default false).
         */
        public static final String TRUTH_MAINTENANCE = "truthMaintenance"; 

        // @todo change default to true?
        public static final String DEFAULT_TRUTH_MAINTENANCE = "false"; 

        /**
         * The property whose value is the name of the {@link ITripleStore} 
         * implementation that will be instantiated.  An {@link InferenceEngine} 
         * will be used to wrap that {@link ITripleStore}.
         */
        public static final String STORE_CLASS = "storeClass";
        
        public static final String DEFAULT_STORE_CLASS = LocalTripleStore.class.getName();

        /**
         * The capacity of the buffers used to absorb writes.
         * 
         * @todo name conflict?
         */
        public static final String BUFFER_CAPACITY = "bufferCapacity";

        public static final String DEFAULT_BUFFER_CAPACITY = "10000";

    }

    /**
     * The equivilent of a null identifier for an internal RDF Value.
     */
    protected static final long NULL = IRawTripleStore.NULL;
    
    protected OptimizedValueFactory valueFactory;

    protected InferenceEngine inf;
    
    protected AbstractTripleStore database;
    
    protected Properties properties;
    
    private int bufferCapacity = -1;
    
    /**
     * When true, the RDFS closure will be maintained.
     */
    private boolean truthMaintenance;
    
    /**
     * When true, the RDFS closure will be maintained by the <em>SAIL</em>
     * implementation (but not by methods that go around the SAIL).
     */
    public boolean getTruthMaintenance() {
        
        return truthMaintenance;
        
    }
    
    /**
     * Used to buffer statements that are being asserted so as to maximize the
     * opportunity for batch writes.  Truth maintenance (if enabled) will be
     * performed no later than the commit of the transaction.
     * <p>
     * Note: When non-empty, the buffer MUST be flushed (a) if it is not empty
     * and a transaction completes (otherwise writes will not be stored on the
     * database); or (b) if there is a read against the database during a
     * transaction (otherwise reads will not see the unflushed statements).
     */
    private IStatementBuffer assertBuffer;
    
    /**
     * Used to collect statements being retracted IFF truth maintenance is
     * enabled.
     */
    private TMStatementBuffer retractBuffer;
    
    /**
     * When true, a SAIL "transaction" is running.
     */
    private boolean transactionStarted;
    
    /**
     * The implementation object.
     */
    public AbstractTripleStore getDatabase() {
        
        return database;
        
    }
    
    /**
     * Create a repository.
     * 
     * @see #initialize(Map)
     */
    public BigdataRdfRepository() {
    }

    /**
     * Alternative constructor used to wrap an existing store and a
     * pre-configured {@link InferenceEngine} - you MUST still invoke
     * {@link #initialize(Map)}.
     * 
     * @param store
     */
    public BigdataRdfRepository(InferenceEngine inf) {

        this.database = inf.database;
        
        this.inf = inf;
        
    }
    
    /**
     * @param configParams
     *            See {@link Options} for the persistence store options.
     *            <p>
     *            The optional boolean option "eagerClosure" may be used to turn
     *            on a simple inference engine that will compute the eager
     *            closure of data loaded into the store via either the SAIL
     *            transaction mechanism or the batch load mechanism. The
     *            selection of this option is NOT restart safe (it is not saved
     *            in the store).
     */
    public void initialize(Map configParams) throws SailInitializationException {

        properties = PropertyUtil.flatCopy(PropertyUtil.convert(configParams));

        String val;

        // truthMaintenance
        {
            val = properties.getProperty(Options.TRUTH_MAINTENANCE);

            if (val != null) {

                truthMaintenance = Boolean.parseBoolean(val);

            } else {

                // No closure by default.
                truthMaintenance = false;

            }

            log.info(Options.TRUTH_MAINTENANCE + "=" + truthMaintenance);
        }
        
        valueFactory = OptimizedValueFactory.INSTANCE;

        if(database==null) {

            /*
             * Create/re-open the database.
             */
            
            final AbstractTripleStore database;
            
            val = properties.getProperty(Options.STORE_CLASS,Options.DEFAULT_STORE_CLASS);

            try {

                Class storeClass = Class.forName(val);

                if(!ITripleStore.class.isAssignableFrom(storeClass)) {
                    
                    throw new SailInitializationException("Must extend "
                            + ITripleStore.class.getName() + " : "
                            + storeClass.getName()); 
                    
                }
                
                Constructor ctor = storeClass.getConstructor(new Class[]{Properties.class});
                
                database = (AbstractTripleStore) ctor.newInstance(new Object[]{properties});

            } catch(SailInitializationException ex) {
                
                throw ex;
                
            } catch(Throwable t) {
                
                throw new SailInitializationException(t);
                
            }
            
            // the database.
            this.database = database;
            
            // inference engine used to maintain RDF(S) closure.
            this.inf = new InferenceEngine(PropertyUtil
                    .convert(configParams), database);
            
        }

        bufferCapacity = Integer.parseInt(properties.getProperty(
                Options.BUFFER_CAPACITY, Options.DEFAULT_BUFFER_CAPACITY));
        
        if(truthMaintenance) {

            assertBuffer = new TMStatementBuffer(inf,bufferCapacity,true/*assertions*/);

            retractBuffer = new TMStatementBuffer(inf,bufferCapacity,true/*assertions*/);
            
        } else {

            assertBuffer = new StatementBuffer(database, bufferCapacity);
            
            retractBuffer = null;  // Not used (deletes are immediate).
            
        }

    }
    
    public void shutDown() {

        /*
         * Note: This is an immediate shutdown.
         */
        
        database.close();
        
    }

    //
    // SailChangedListener support.
    //

    /**
     * Vector of transient {@link SailChangedListener}s registered with this
     * SAIL.
     */
    private Vector<SailChangedListener> m_listeners = null;
    private boolean m_stmtAdded = false;
    private boolean m_stmtRemoved = false;    

    public void addListener(SailChangedListener listener) {

        if( m_listeners == null ) {
            
            m_listeners = new Vector<SailChangedListener>();
            
            m_listeners.add( listener );
            
        } else {
            
            if( m_listeners.contains( listener ) ) {
                
                throw new IllegalStateException
                    ( "Already registered: listener="+listener
                      );
                
            }
            
            m_listeners.add( listener );
            
        }

    }

    public void removeListener(SailChangedListener listener) {

        if( m_listeners == null ) {
            
            throw new IllegalStateException
                ( "Not registered: listener="+listener
                  );
            
        }
        
        if( ! m_listeners.remove( listener ) ) {
            
            throw new IllegalStateException
                ( "Not registered: listener="+listener
                  );
            
        }

    }

    /**
     * Notifies {@link SailChangeListener}s if one or more statements
     * have been added to or removed from the repository using the SAIL
     * methods: <ul>
     * 
     * <li> {@link #addStatement(Resource, URI, Value)}
     * <li> {@link #removeStatements(Resource, URI, Value)}
     * <li> {@link #clearRepository()}
     * </ul>
     */
    synchronized protected void fireSailChangedEvents()
    {
        
        if( m_listeners == null ) return;
        
        if( ! m_stmtAdded && ! m_stmtRemoved ) return;

        SailChangedEvent e = new SailChangedEventImpl
            ( m_stmtAdded,
              m_stmtRemoved
              );
        
        SailChangedListener[] listeners = (SailChangedListener[]) 
            m_listeners.toArray( new SailChangedListener[]{} );

        for( int i=0; i<listeners.length; i++ ) {
            
            SailChangedListener l = listeners[ i ];
            
            l.sailChanged( e );
                
        }
        
    }

    public void addStatement(Resource s, URI  p, Value o)
            throws SailUpdateException {

        assertTransactionStarted();

        // flush any pending retractions first!
        
        if(retractBuffer!=null && !retractBuffer.isEmpty()) {
            
            retractBuffer.doClosure();
            
        }

        s = (Resource) valueFactory.toNativeValue(s);

        p = (URI) valueFactory.toNativeValue(p);

        o = (Value) valueFactory.toNativeValue(o);
        
        // buffer the assertion.
        
        assertBuffer.add(s, p, o);
        
    }

    public void changeNamespacePrefix(String namespace, String prefix)
            throws SailUpdateException {

        assertTransactionStarted();

        database.addNamespace(namespace,prefix);
        
    }

    public NamespaceIterator getNamespaces() {

        /*
         * Note: You do NOT need to flush the buffer since this does not read
         * statements.
         */

        return new MyNamespaceIterator(database.getNamespaces().entrySet().iterator());

    }

    private class MyNamespaceIterator implements NamespaceIterator {

        private final Iterator<Map.Entry<String/*namespace*/,String/*prefix*/>> src;
        
        private Map.Entry<String/*namespace*/,String/*prefix*/> current = null;
        
        public MyNamespaceIterator(Iterator<Map.Entry<String/*namespace*/,String/*prefix*/>> src) {
            
            assert src != null;
            
            this.src = src;
            
        }
        
        public boolean hasNext() {
            
            return src.hasNext();
            
        }

        public void next() {
         
            current = src.next();
            
        }

        public String getName() {
            
            if(current==null) throw new IllegalStateException();
            
            return current.getKey();
            
        }

        public String getPrefix() {

            if(current==null) throw new IllegalStateException();
            
            return current.getValue();
               
        }

        public void close() {
            
            // NOP.
            
        }
        
    }
    
    /**
     * Note: Since there is only one RdfRepository per persistence store, the
     * easiest way to achive this end is to delete the persistence store and
     * open/create a new one.
     */
    public void clearRepository() throws SailUpdateException {

        assertTransactionStarted();

        // discard any pending asserts.
        assertBuffer.clear();
        
        if(retractBuffer!=null) {
        
            // discard any pending retracts.
            retractBuffer.clear();
            
        }
        
        // clear the database.
        database.clear();
        
        m_stmtRemoved = true;
        
    }

    public int removeStatements(Resource s, URI p, Value o)
            throws SailUpdateException {
        
        assertTransactionStarted();
        
        // flush any pending assertions first!
        
        assertBuffer.flush();
        
        if(getTruthMaintenance()) {
        
            // do truth maintenance.
            
            ((TMStatementBuffer)assertBuffer).doClosure();
            
        }

        // #of explicit statements removed.
        final int n;

        if (getTruthMaintenance()) {

            /*
             * Since we are doing truth maintenance we need to copy the matching
             * "explicit" statements into a temporary store rather than deleting
             * them directly. This uses the internal API to copy the statements
             * to the temporary store without materializing them as Sesame
             * Statement objects.
             * 
             * @todo add an ISPOFilter parameter to IAccessPath#iterator() so
             * that we can send the filter to the database rather than filtering
             * on the client.
             */
            
            // obtain a chunked iterator using the triple pattern.
            ISPOIterator itr = database.getAccessPath(s,p,o).iterator();
            
            // copy explicit statements to the temporary store.
            n = retractBuffer.getStatementStore().addStatements(itr, new ISPOFilter() {

                public boolean isMatch(SPO spo) {
               
                    // only copy explicit statements.

                    return spo.type==StatementEnum.Explicit;
                    
                }
                
            });

        } else {

            /*
             * Since we are not doing truth maintenance, just remove the
             * statements from the database (synchronous, batch api, not
             * buffered).
             */
            
            n = database.removeStatements(s, p, o);

        }

        if (n > 0) {

            m_stmtRemoved = true;
            
        }
        
        return n;

    }

    public void startTransaction() {
        
        if(transactionStarted) {
            
            throw new SailInternalException(
                    "A transaction was already started.");

        }
        
        transactionStarted = true;
        
        m_stmtAdded = false;

        m_stmtRemoved = false;

    }

    public boolean transactionStarted() {

        return transactionStarted;
        
    }

    public void commitTransaction() {

        if( ! transactionStarted ) {
            
            throw new SailInternalException
                ( "No transaction has been started."
                  );

        }

        /*
         * Flush any pending writes.
         * 
         * Note: This must be done before you compute the closure so that the
         * pending writes will be read by the inference engine when it computes
         * the closure.
         */
        
        flushStatementBuffers();
        
        database.commit();
        
        if(true) database.dumpStore();
        
        transactionStarted = false;
        
        fireSailChangedEvents();

    }

    /**
     * Flush any pending assertions or retractions to the database using
     * efficient batch operations. If {@link #getTruthMaintenance()} returns
     * <code>true</code> this method will also handle truth maintenance.
     * <p>
     * Note: This tests whether or not a transaction has been started. It MUST
     * be invoked within any method that will read on the database to ensure
     * that any pending writes have been flushed (otherwise the read operation
     * will not be able to see the pending writes). However, methods that assert
     * or retract statements MUST only flush the buffer on which they will NOT
     * write.  E.g., if you are going to retract statements, then first flush
     * the assertions buffer and visa versa.
     */
    protected void flushStatementBuffers() {

        if (transactionStarted) {

            if (assertBuffer != null && !assertBuffer.isEmpty()) {

                assertBuffer.flush();
                
                if(getTruthMaintenance()) {

                    ((TMStatementBuffer)assertBuffer).doClosure();
                    
                }

                m_stmtAdded = true;

            }

            if (retractBuffer != null && !retractBuffer.isEmpty()) {

                retractBuffer.doClosure();
                
                m_stmtRemoved = true;

            }
            
        }
        
    }
    
    protected void assertTransactionStarted() {

        if (!transactionStarted) {

            throw new SailInternalException("No transaction has been started");

        }
        
    }
    
    /**
     * Returns an iterator that visits {@link StatementWithType} objects.
     */
    public StatementIterator getStatements(Resource s, URI p, Value o) {

        flushStatementBuffers();

        IAccessPath accessPath = database.getAccessPath(s, p, o);

        if(accessPath instanceof EmptyAccessPath) {
            
            return new EmptyStatementIterator();
            
        }
        
        ISPOIterator src = accessPath.iterator();
        
        if(getTruthMaintenance() && !inf.getForwardChainRdfTypeRdfsResource()) {
            
            /*
             * Since the inference engine is not computing and storing (x type
             * resource) we need to backchain it now.
             */
            
            long[] ids = accessPath.getTriplePattern();
            
            src = new BackchainTypeResourceIterator(
                    src,// the source iterator.
                    ids[0], ids[1], ids[2], // the triple pattern.
                    database,// the database
                    inf.rdfType.id,//
                    inf.rdfsResource.id//
                    );
            
        }
        
        return database.asStatementIterator(src);
        
    }
        
    public ValueFactory getValueFactory() {
        
        return valueFactory;
        
    }

    public boolean hasStatement(Resource s, URI p, Value o) {

        flushStatementBuffers();
        
        return database.hasStatement(s, p, o);
        
    }

    /**
     * 
     */
    public Query optimizeQuery(Query query) {
        
        flushStatementBuffers();
        
        /*
         * This static method is provided by the Sesame framework and performs a
         * variety of default optimizations.
         */

//        QueryOptimizer.optimizeQuery(query);
        
        /*
         * This variant is based on the Sesame optimizer but it uses range
         * counts to order triple patterns based on their actual selectiveness
         * in the data at the time that the query is run.  This can be a big
         * win depending on the query.
         */
        optimizeQuery2(query);

        /*
         * Replace all Value objects stored in variables with the corresponding
         * _Value objects.
         */

        replaceValuesInQuery
            ( query
              );

        return query;
        
    }

    /**
     * An attempt to get the Sesame query optimizer to choose the join
     * order based on the actual selectivity of the triple patterns.
     * 
     * @param qc
     */
    private void optimizeQuery2(Query qc) {
        if (qc instanceof GraphPatternQuery) {
            GraphPatternQuery gpQuery = (GraphPatternQuery)qc;
            _optimizeGraphPattern(gpQuery.getGraphPattern(), new HashSet());
        }
        else if (qc instanceof SetOperator) {
            SetOperator setOp = (SetOperator)qc;
            optimizeQuery( setOp.getLeftArg() );
            optimizeQuery( setOp.getRightArg() );
        }
    }

    private void _optimizeGraphPattern(GraphPattern graphPattern, Set boundVars) {
        // Optimize any optional child graph patterns:
        Iterator iter = graphPattern.getOptionals().iterator();

        if (iter.hasNext()) {
            // Build set of variables that are bound in this scope
            Set scopeVars = new HashSet(boundVars);
            graphPattern.getLocalVariables(scopeVars);

            // Optimize recursively
            while (iter.hasNext()) {
                GraphPattern optionalGP = (GraphPattern)iter.next();
                _optimizeGraphPattern(optionalGP, new HashSet(scopeVars));
            }
        }

        // Optimize the GraphPattern itself:
        _inlineVarAssignments(graphPattern);
        _orderExpressions(graphPattern, boundVars);
    }
    
    /**
     * Inlines as much of the "variable assignments" (comparison between a
     * variable and fixed value) that are found in the list of conjunctive
     * constraints as possible, and removes them from the query. Only variable
     * assignments for variables that are used in <tt>graphPattern</tt> itself
     * are processed. Inlining variable assignments for variables that are
     * (only) used in optional child graph patterns leads to incorrect query
     * evaluation.
     **/
    private void _inlineVarAssignments(GraphPattern graphPattern) {
        Set localVars = new HashSet();
        graphPattern.getLocalVariables(localVars);

        boolean constraintsModified = false;

        List conjunctiveConstraints =
                new ArrayList(graphPattern.getConjunctiveConstraints());

        Iterator iter = conjunctiveConstraints.iterator();

        while (iter.hasNext()) {
            BooleanExpr boolExpr = (BooleanExpr)iter.next();

            if (boolExpr instanceof ValueCompare) {
                ValueCompare valueCompare = (ValueCompare)boolExpr;

                if (valueCompare.getOperator() != ValueCompare.EQ) {
                    continue;
                }

                ValueExpr arg1 = valueCompare.getLeftArg();
                ValueExpr arg2 = valueCompare.getRightArg();

                Var varArg = null;
                Value value = null;

                if (arg1 instanceof Var && arg1.getValue() == null && // arg1 is an unassigned var 
                    arg2.getValue() != null) // arg2 has a value
                {
                    varArg = (Var)arg1;
                    value = arg2.getValue();
                }
                else if (arg2 instanceof Var && arg2.getValue() == null && // arg2 is an unassigned var
                    arg1.getValue() != null) // arg1 has a value
                {
                    varArg = (Var)arg2;
                    value = arg1.getValue();
                }

                if (varArg != null && localVars.contains(varArg)) {
                    // Inline this variable assignment
                    varArg.setValue(value);

                    // Remove the (now redundant) constraint
                    iter.remove();

                    constraintsModified = true;
                }
            }
        }

        if (constraintsModified) {
            graphPattern.setConstraints(conjunctiveConstraints);
        }
    }
    
    /**
     * Merges the boolean constraints and the path expressions in one single
     * list. The order of the path expressions is not changed, but the boolean
     * constraints are inserted between them. The separate boolean constraints
     * are moved to the start of the list as much as possible, under the
     * condition that all variables that are used in the constraint are
     * instantiated by the path expressions that are earlier in the list. An
     * example combined list might be:
     * <tt>[(A,B,C), A != foo:bar, (B,E,F), C != F, (F,G,H)]</tt>.
     **/
    private void _orderExpressions(GraphPattern graphPattern, Set boundVars) {
        List expressions = new ArrayList();
        List conjunctiveConstraints = new LinkedList(graphPattern.getConjunctiveConstraints());

        // First evaluate any constraints that don't depend on any variables:
        _addVerifiableConstraints(conjunctiveConstraints, boundVars, expressions);

        // Then evaluate all path expressions from graphPattern
        List pathExpressions = new LinkedList(graphPattern.getPathExpressions());
        Hashtable<PathExpression,Integer> rangeCounts = new Hashtable<PathExpression, Integer>();
        while (!pathExpressions.isEmpty()) {
            PathExpression pe = _getMostSpecificPathExpression(pathExpressions, boundVars, rangeCounts);

            pathExpressions.remove(pe);
            expressions.add(pe);

            pe.getVariables(boundVars);

            _addVerifiableConstraints(conjunctiveConstraints, boundVars, expressions);
        }

        // Finally, evaluate any optional child graph pattern lists
        List optionals = new LinkedList(graphPattern.getOptionals());
        while (!optionals.isEmpty()) {
            PathExpression pe = _getMostSpecificPathExpression(optionals, boundVars, rangeCounts);

            optionals.remove(pe);
            expressions.add(pe);

            pe.getVariables(boundVars);

            _addVerifiableConstraints(conjunctiveConstraints, boundVars, expressions);
        }

        // All constraints should be verifiable when all path expressions are
        // evaluated, but add any remaining constraints anyway
        expressions.addAll(conjunctiveConstraints);

        graphPattern.setExpressions(expressions);
    }

    /**
     * Gets the most specific path expression from <tt>pathExpressions</tt>
     * given that the variables in <tt>boundVars</tt> have already been assigned
     * values. The most specific path expressions is the path expression with
     * the least number of unbound variables.
     **/
    private PathExpression _getMostSpecificPathExpression(
            List pathExpressions, Set boundVars, Hashtable<PathExpression,Integer> rangeCounts)
    {
        int minVars = Integer.MAX_VALUE;
        int minRangeCount = Integer.MAX_VALUE;
        PathExpression result = null;
        ArrayList vars = new ArrayList();

        for (int i = 0; i < pathExpressions.size(); i++) {
            PathExpression pe = (PathExpression)pathExpressions.get(i);

            /*
             * The #of results for this PathException or -1 if not a
             * TriplePattern.
             * 
             * @todo if zero (0), then at least order it first.
             */
            int rangeCount = getRangeCount(pe,rangeCounts);
            
            // Get the variables that are used in this path expression
            vars.clear();
            pe.getVariables(vars);

            // Count unbound variables
            int varCount = 0;
            for (int j = 0; j < vars.size(); j++) {
                Var var = (Var)vars.get(j);

                if (!var.hasValue() && !boundVars.contains(var)) {
                    varCount++;
                }
            }

            // A bit of hack to make sure directType-, directSubClassOf- and
            // directSubPropertyOf patterns get sorted to the back because these
            // are potentially more expensive to evaluate.
            if (pe instanceof DirectType ||
                pe instanceof DirectSubClassOf ||
                pe instanceof DirectSubPropertyOf)
            {
                varCount++;
            }

            if (rangeCount != -1) {
                // rangeCount is known for this path expression.
                if (rangeCount < minRangeCount) {
                    // More specific path expression found
                    minRangeCount = rangeCount;
                    result = pe;
                }
            } else {
                // rangeCount is NOT known.
                if (varCount < minVars) {
                    // More specific path expression found
                    minVars = varCount;
                    result = pe;
                }
            }
        }

        return result;
    }

    /**
     * Estimate the #of results for a triple pattern.
     * <p>
     * Note: This MAY over-estimate since deleted entries that have not been
     * purged will be counted if an index supports isolation.
     * 
     * @param pe
     * @param rangeCounts
     * 
     * @return The estimated range count or <code>-1</code> if this is not a
     *         {@link TriplePattern}.
     */
    private int getRangeCount(PathExpression pe,Hashtable<PathExpression,Integer> rangeCounts) {
        
        Integer rangeCount = rangeCounts.get(pe);
        
        if(rangeCount!=null) {
            
            return rangeCount;
            
        }
        
        if(pe instanceof TriplePattern) {
            
            TriplePattern tp = (TriplePattern)pe;
            
            rangeCount = rangeCount(tp);

            log.info("rangeCount: " + rangeCount + " : " + pe);
            
            rangeCounts.put(pe,rangeCount);
            
            return rangeCount.intValue();
            
        } else {
            
            return -1;
            
        }
        
    }

    private int rangeCount(TriplePattern tp) {
        
        /*
         * Extract "variables". If hasValue() is true, then the variable is
         * actually bound.
         */
        Var svar = tp.getSubjectVar();
        Var pvar = tp.getPredicateVar();
        Var ovar = tp.getObjectVar();
        
        /*
         * Extract binding for variable or null iff not bound.
         */
        Resource s = svar.hasValue()?(Resource)svar.getValue():null;
    
        URI p = pvar.hasValue()?(URI)pvar.getValue():null;
        
        Value o = ovar.hasValue()?ovar.getValue():null;
        
        /*
         * convert other Value object types to our object types.
         */
        if (s != null)
            s = (Resource) valueFactory.toNativeValue(s);

        if (p != null)
            p = (URI) valueFactory.toNativeValue(p);

        if (o != null)
            o = (Value) valueFactory.toNativeValue(o);
        
        /*
         * convert our object types to internal identifiers.
         */
        long _s, _p, _o;

        _s = (s == null ? NULL : database.getTermId(s));
        _p = (p == null ? NULL : database.getTermId(p));
        _o = (o == null ? NULL : database.getTermId(o));

        /*
         * If a value was specified and it is not in the terms index then the
         * statement can not exist in the KB.
         */
        if (_s == NULL && s != null) {

            return 0;
            
        }
        
        if (_p == NULL && p != null) {
        
            return 0;
            
        }
        
        if (_o == NULL && o != null) {
            
            return 0;
            
        }
        
        return database.getAccessPath(_s, _p, _o).rangeCount();

    }
    
    /**
     * Adds all verifiable constraints (constraint for which every variable has
     * been bound to a specific value) from <tt>conjunctiveConstraints</tt> to
     * <tt>expressions</tt>.
     *
     * @param conjunctiveConstraints A List of BooleanExpr objects.
     * @param boundVars A Set of Var objects that have been bound.
     * @param expressions The list to add the verifiable constraints to.
     **/
    private void _addVerifiableConstraints(
        List conjunctiveConstraints, Set boundVars, List expressions)
    {
        Iterator iter = conjunctiveConstraints.iterator();

        while (iter.hasNext()) {
            BooleanExpr constraint = (BooleanExpr)iter.next();

            Set constraintVars = new HashSet();
            constraint.getVariables(constraintVars);

            if (boundVars.containsAll(constraintVars)) {
                // constraint can be verified
                expressions.add(constraint);
                iter.remove();
            }
        }
    }

    /**
     * Replace all {@link Value} objects stored in variables with the
     * corresponding {@link _Value} objects.
     * <p>
     * 
     * Note: This can cause unknown terms to be inserted into store.
     */
    private void replaceValuesInQuery(Query query) {

        final List varList = new ArrayList();

        query.getVariables(varList);

        for (int i = 0; i < varList.size(); i++) {

            final Var var = (Var) varList.get(i);

            if (var.hasValue()) {

                final Value value = var.getValue();

                if (value instanceof URI) {

                    // URI substitution.

                    String uriString = ((URI) value).getURI();

                    var.setValue(valueFactory.createURI(uriString));

                } else if (value instanceof BNode) {

                    // BNode substitution.

                    final String id = ((BNode) value).getID();

                    if (id == null) {

                        var.setValue(valueFactory.createBNode());

                    } else {

                        var.setValue(valueFactory.createBNode(id));

                    }

                } else if (value instanceof Literal) {

                    // Literal substitution.

                    Literal lit = (Literal) value;

                    final String lexicalForm = lit.getLabel();

                    final String language = lit.getLanguage();

                    if (language != null) {

                        lit = valueFactory.createLiteral(lexicalForm, language);

                    } else {

                        URI datatype = lit.getDatatype();

                        if (datatype != null) {

                            lit = valueFactory.createLiteral(lexicalForm,
                                    datatype);

                        } else {

                            lit = valueFactory.createLiteral(lexicalForm);

                        }

                    }

                    var.setValue(lit);

                } // if( literal )

            } // if( hasValue )

        } // next variable.

    }

    /**
     * Computes the closure of the triple store for RDFS entailments.
     * <p>
     * This computes the full forward closure of the store and then commits the
     * store. This can be used if you do NOT enable truth maintenance and choose
     * instead to load up all of your data first and then compute the closure of
     * the database.
     * <p>
     * This method lies outside of the SAIL and does not rely on the SAIL
     * "transaction" mechanisms.
     * 
     * @todo offer a method to retract any entailments so that people can choose
     *       to re-close a database. that method will probably be defined by
     *       {@link TMStatementBuffer}.
     */
    public void fullForwardClosure() {
        
        flushStatementBuffers();
        
        inf.computeClosure(null/*focusStore*/);
        
        database.commit();
                
    }
    
}
