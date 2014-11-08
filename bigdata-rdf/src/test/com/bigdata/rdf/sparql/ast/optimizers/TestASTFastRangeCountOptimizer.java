/**

Copyright (C) SYSTAP, LLC 2006-2011.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

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
 * Created on Sep 15, 2011
 */

package com.bigdata.rdf.sparql.ast.optimizers;

import com.bigdata.rdf.sparql.ast.FunctionRegistry;
import com.bigdata.rdf.sparql.ast.StatementPatternNode;
import com.bigdata.rdf.sparql.ast.VarNode;
import com.bigdata.rdf.sparql.ast.optimizers.AbstractOptimizerTestCase.Annotations;
import com.bigdata.rdf.spo.DistinctTermAdvancer;

/**
 * Test suite for {@link ASTFastRangeCountOptimizer}. This needs to handle a
 * variety of things related to the following, including where variables are
 * projected into a sub-select (in which case we run the fast range count using
 * the as-bound variables for the triple pattern).
 * 
 * <pre>
 * SELECT COUNT(...) (DISTINCT|REDUCED) {single-triple-pattern}
 * </pre>
 * 
 * @see ASTFastRangeCountOptimizer
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class TestASTFastRangeCountOptimizer extends AbstractOptimizerTestCase {

	public TestASTFastRangeCountOptimizer() {
		super();
	}

	public TestASTFastRangeCountOptimizer(final String name) {
		super(name);
	}

	@Override
	IASTOptimizer newOptimizer() {

		return new ASTOptimizerList(new ASTFastRangeCountOptimizer());
		
	}

	/**
	 * 
	 * <pre>
	 * SELECT (COUNT(*) as ?w) {?s ?p ?o}
	 * </pre>
	 */
	public void test_fastRangeCountOptimizer_01() {

    	new Helper(){{

//    		originalAST
//    		QueryType: SELECT
//    		SELECT ( com.bigdata.rdf.sparql.ast.FunctionNode(VarNode(*))[ FunctionNode.scalarVals=null, FunctionNode.functionURI=http://www.w3.org/2006/sparql-functions#count, valueExpr=com.bigdata.bop.rdf.aggregate.COUNT(*)] AS VarNode(count) )
//    		  JoinGroupNode {
//    		    StatementPatternNode(VarNode(s), VarNode(p), VarNode(o)) [scope=DEFAULT_CONTEXTS]
//    		  }
				given = select(
						projection(bind(
								functionNode(
										FunctionRegistry.COUNT.stringValue(),
										new VarNode("*")), varNode(w))),
						where(newStatementPatternNode(new VarNode(s),
								new VarNode(p), new VarNode(o))));

				/**
				 * We need to convert:
				 * 
				 * <pre>
				 * SELECT (COUNT(*) as ?w) {?s ?p ?o}
				 * </pre>
				 * 
				 * into
				 * 
				 * <pre>
				 * SELECT ?w {?s ?p ?o}
				 * </pre>
				 * 
				 * where the triple pattern has been marked with the
				 * FAST-RANGE-COUNT:=?w annotation; and
				 * 
				 * where the ESTIMATED_CARDINALITY of the triple pattern has
				 * been set to ONE (since we will not use a scan).
				 * 
				 * This means that the triple pattern will be directly
				 * interpreted as binding ?w to the ESTCARD of the triple
				 * pattern.
				 */
				// the triple pattern.
				final StatementPatternNode sp1 = newStatementPatternNode(
						new VarNode(s), new VarNode(p), new VarNode(o));
				// annotate with the name of the variable to become bound to the
				// fast range count of that triple pattern.
				sp1.setFastRangeCount(new VarNode(w));
				sp1.setProperty(Annotations.ESTIMATED_CARDINALITY, 1L);

				// the expected AST.
				expected = select(projection(varNode(w)), where(sp1));

			}
		}.test();
    	
    }

	/**
	 * TODO Do a test to make sure that this optimizer is disabled if the KB
	 * uses full read/write transactions.
	 */
	public void test_fastRangeCountOptimizer_xx() {
		fail("more coverage of different cases");
	}

	/**
	 * FIXME Handle these cases.
	 * 
	 * This case is quite similar structurally to the fast-range-count but it
	 * gets translated into a {@link DistinctTermAdvancer} in a physical
	 * operator which then counts the number of distinct terms that are visited.
	 * 
	 * <pre>
	 * SELECT (COUNT(?s) as ?w) {?s ?p ?o}
	 * </pre>
	 * 
	 * This case does not include the COUNT(). It is translated into a
	 * {@link DistinctTermAdvancer} and the distinct terms are simply bound onto
	 * ?s.
	 * 
	 * Note: This is only possible when the values of the other variables are
	 * ignored.
	 * 
	 * <pre>
	 * SELECT ?s {?s ?p ?o}
	 * </pre>
	 * 
	 */
	public void test_distinctTermScanOptimizer_01() {

		fail("write tests");
    	
    }

}
