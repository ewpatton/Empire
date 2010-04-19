/*
 * Copyright (c) 2009-2010 Clark & Parsia, LLC. <http://www.clarkparsia.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.clarkparsia.empire.impl;

import com.clarkparsia.empire.DataSource;
import com.clarkparsia.empire.QueryFactory;
import com.clarkparsia.empire.Empire;
import com.clarkparsia.empire.Dialect;

import javax.persistence.Query;
import javax.persistence.NamedQuery;
import javax.persistence.QueryHint;
import javax.persistence.NamedQueries;

import java.util.HashMap;
import java.util.Map;
import java.util.Collection;

/**
 * <p>Implements the common operations of a {@link QueryFactory} and defers query language specific operations
 * to concrete implementations of this class.</p>
 *
 * @author Michael Grove
 * @since 0.1
 * @version 0.6.4
 */
public class RdfQueryFactory implements QueryFactory {
	/**
	 * the data source the queries will be executed against
	 */
	private DataSource mSource;

	/**
	 * The query language dialect supported by this factory
	 */
	private Dialect mDialect;

	/**
	 * User-defined NamedQueries.  The actual queries are evaluated on-demand, we'll just keep the annotations which
	 * contain the information needed to create them here.
	 */
	private Map<String, NamedQuery> mNamedQueries = new HashMap<String, NamedQuery>();

	/**
	 * Create a new AbstractQueryFactory
	 * @param theSource the data source the queries will be executed against
	 * @param theDialect the Query dialect supporte by this query factory
	 */
	public RdfQueryFactory(final DataSource theSource, Dialect theDialect) {
		mSource = theSource;
		mDialect = theDialect;

		Collection<Class<?>> aClasses = Empire.get().getAnnotationProvider().getClassesWithAnnotation(NamedQuery.class);
		for (Class<?> aClass :  aClasses) {
			NamedQuery aNamedQuery = aClass.getAnnotation(NamedQuery.class);

			addNamedQuery(aNamedQuery.name(), aNamedQuery);
		}

		aClasses = Empire.get().getAnnotationProvider().getClassesWithAnnotation(NamedQueries.class);
		for (Class<?> aClass : aClasses) {
			NamedQueries aNamedQueries = aClass.getAnnotation(NamedQueries.class);

			for (NamedQuery aQuery : aNamedQueries.value()) {
				addNamedQuery(aQuery.name(), aQuery);
			}
		}
	}

	/**
	 * Create a new Query against the current data source with the given query string
	 * @param theQuery the query string
	 * @return a new query
	 */
	protected RdfQuery newQuery(String theQuery) {
		return new RdfQuery(mSource, theQuery);
	}

	/**
	 * Return the data source the queries will be executed against
	 * @return the data source
	 */
	protected DataSource getSource() {
		return mSource;
	}

	/**
	 * Add a query with the given name to this factory
	 * @param theName the name of the query
	 * @param theQuery the query identified by the given name
	 */
	private void addNamedQuery(final String theName, final NamedQuery theQuery) {
		mNamedQueries.put(theName, theQuery);
	}

	/**
	 * @inheritDoc
	 */
	public Dialect getDialect() {
		return mDialect;
	}

	/**
	 * @inheritDoc
	 */
	public Query createQuery(final String theQueryString) {
		return newQuery(theQueryString);
	}

	/**
	 * @inheritDoc
	 */
	public Query createNamedQuery(final String theName) {
		if (mNamedQueries.containsKey(theName)) {
			NamedQuery aNamedQuery = mNamedQueries.get(theName);

			RdfQuery aQuery = newQuery(aNamedQuery.query());
			for (QueryHint aHint : aNamedQuery.hints()) {
				aQuery.setHint(aHint.name(), aHint.value());
			}

			aQuery.setSource(mSource);

			return aQuery;
		}
		else {
			throw new IllegalArgumentException("Query named '" + theName + "' does not exist.");
		}
	}

	/**
	 * @inheritDoc
	 */
	public Query createNativeQuery(final String theQueryString) {
		return newQuery(theQueryString);
	}

	/**
	 * @inheritDoc
	 */
	public Query createNativeQuery(final String theQueryString, final Class theResultClass) {
		RdfQuery aQuery = newQuery(theQueryString);

		aQuery.setBeanClass(theResultClass);

		return aQuery;
	}

	/**
	 * @inheritDoc
	 */
	public Query createNativeQuery(final String theQueryString, final String theResultSetMapping) {
		throw new UnsupportedOperationException();
	}
}
