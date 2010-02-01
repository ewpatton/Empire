package com.clarkparsia.empire.test.api;

import com.clarkparsia.empire.MutableDataSource;
import com.clarkparsia.empire.DataSourceException;

import org.openrdf.model.Graph;

/**
 * <p>Implementation of the MutableDataSource interface for testing</p>
 *
 * @author Michael Grove
 */
public class MutableTestDataSource extends TestDataSource implements MutableDataSource {

	public MutableTestDataSource() {
        super();
	}

	public MutableTestDataSource(final Graph theGraph) {
		super(theGraph);
	}

	/**
	 * @inheritDoc
	 */
	public void add(final Graph theGraph) throws DataSourceException {
		try {
			getRepository().addGraph(theGraph);
		}
		catch (Exception e) {
			throw new DataSourceException(e);
		}
	}

	/**
	 * @inheritDoc
	 */
	public void remove(final Graph theGraph) throws DataSourceException {
		try {
			getRepository().removeGraph(theGraph);
		}
		catch (Exception e) {
			throw new DataSourceException(e);
		}
	}
}
