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

import com.clarkparsia.empire.DataSourceException;
import com.clarkparsia.empire.MutableDataSource;
import com.clarkparsia.empire.SupportsNamedGraphs;
import com.clarkparsia.empire.SupportsRdfId;
import com.clarkparsia.empire.SupportsTransactions;
import com.clarkparsia.empire.Empire;

import com.clarkparsia.empire.annotation.InvalidRdfException;
import com.clarkparsia.empire.annotation.RdfGenerator;
import com.clarkparsia.empire.annotation.RdfsClass;

import org.openrdf.model.Graph;
import org.openrdf.model.Statement;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import javax.persistence.EntityExistsException;
import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.EntityTransaction;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceException;
import javax.persistence.Query;

import javax.persistence.Entity;
import javax.persistence.PrePersist;
import javax.persistence.EntityListeners;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;
import javax.persistence.PostLoad;
import javax.persistence.PreRemove;
import javax.persistence.PreUpdate;

import java.lang.annotation.Annotation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.AccessibleObject;

import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Collections;
import java.util.WeakHashMap;

import static com.clarkparsia.empire.util.BeanReflectUtil.getAnnotatedFields;
import static com.clarkparsia.empire.util.BeanReflectUtil.getAnnotatedGetters;
import static com.clarkparsia.empire.util.BeanReflectUtil.asSetter;
import static com.clarkparsia.empire.util.BeanReflectUtil.safeGet;
import static com.clarkparsia.empire.util.BeanReflectUtil.safeSet;
import static com.clarkparsia.empire.util.BeanReflectUtil.hasAnnotation;
import com.clarkparsia.empire.util.EmpireUtil;
import com.clarkparsia.empire.util.BeanReflectUtil;
import com.clarkparsia.utils.Predicate;
import com.clarkparsia.utils.AbstractDataCommand;

/**
 * <p>Implementation of the JPA {@link EntityManager} interface to support the persistence model over
 * an RDF database.</p>
 *
 * @author Michael Grove
 * @since 0.1
 * @version 0.6.3
 * @see EntityManager
 * @see com.clarkparsia.empire.DataSource
 */
public class EntityManagerImpl implements EntityManager {
	/**
	 * The logger
	 */
	private static final Logger LOGGER = LogManager.getLogger(EntityManagerImpl.class.getName());

	/**
	 * Whether or not this EntityManagerImpl is open
	 */
	private boolean mIsOpen = false;

	/**
	 * The underlying data source
	 */
	private MutableDataSource mDataSource;

	/**
	 * The DataSource wrapped in Transaction support
	 */
	private SupportsTransactions mTransactionSource;

	/**
	 * The current transaction
	 */
	private EntityTransaction mTransaction;

	/**
	 * The Entity Listeners for our managed entities.
	 */
	private Map<Object, Collection<Object>> mManagedEntityListeners = new WeakHashMap<Object, Collection<Object>>();

	/**
	 * Create a new EntityManagerImpl
	 * @param theSource the underlying RDF datasource used for persistence operations
	 */
	EntityManagerImpl(MutableDataSource theSource) {

		// TODO: sparql for everything, just convert serql into sparql
		// TODO: bnode support?
		// TODO: locking support
		// TODO: work like JPA/hibernate -- if something does not have a @Transient on it, convert it.  we'll just need to coin a URI in those cases
		// TODO: support cascades of delete's -- when you delete a resource X, right now we just delete all the triples
		// where X is the subject.  allow you to specify a cascade so you can remove all the triples where X is the object
		// actually, we want all operations to be cascable (or not) via @CascadeType
		// TODO: add an @RdfsLabel annotation that will use the value of a property as the label during annotation
		// TODO: implement as many of the normal JPA annotations as possible/reasonable (like @Transient, @Enumerated, @ManyToOne) mapping to OWL/rdfs constructs as appropriate
		//       -- need list of candidate annotations
		// TODO: support for owl/rdfs annotations not mappable to JPA annotations such as min/max cardinality and others.
		// TODO: do we want to do anything special with MappedSuperclass?

		mIsOpen = true;

		mDataSource = theSource;

		if (theSource instanceof SupportsTransactions) {
			mTransactionSource = (SupportsTransactions) theSource;
		}
		else {
			// it doesnt support transactions natively, so we'll wrap it in our naive transaction support.
			mTransactionSource = new TransactionalDataSource(theSource);
		}
	}

	/**
	 * @inheritDoc
	 */
	public void flush() {
		assertOpen();
		
		// we'll do nothing here since our default implementation doesn't queue up changes, they're made
		// as soon as remove/persist are called
	}

	/**
	 * @inheritDoc
	 */
	public void setFlushMode(final FlushModeType theFlushModeType) {
		assertOpen();

		if (theFlushModeType != FlushModeType.AUTO) {
			throw new IllegalArgumentException("Commit style flush mode not supported");
		}
	}

	/**
	 * @inheritDoc
	 */
	public FlushModeType getFlushMode() {
		assertOpen();
		
		return FlushModeType.AUTO;
	}

	/**
	 * @inheritDoc
	 */
	public void lock(final Object theObj, final LockModeType theLockModeType) {
		throw new PersistenceException("Lock is not supported.");
	}
	
	/**
	 * @inheritDoc
	 */
	public void refresh(Object theObj) {
		assertStateOk(theObj);

		assertContains(theObj);

		Object aDbObj = find(theObj.getClass(), EmpireUtil.asSupportsRdfId(theObj).getRdfId());

        Collection<AccessibleObject> aAccessors = new HashSet<AccessibleObject>();

        aAccessors.addAll(getAnnotatedFields(aDbObj.getClass()));
        aAccessors.addAll(getAnnotatedGetters(aDbObj.getClass(), true));

        try {
            for (AccessibleObject aAccess : aAccessors) {
                Object aValue = safeGet(aAccess, aDbObj);

                AccessibleObject aSetter = asSetter(aDbObj.getClass(), aAccess);

                safeSet(aSetter, theObj, aValue);
            }
        }
        catch (InvocationTargetException e) {
            throw new PersistenceException(e);
        }
    }

	/**
	 * @inheritDoc
	 */
	public void clear() {
		assertOpen();

		cleanState();
	}

	/**
	 * @inheritDoc
	 */
	public boolean contains(final Object theObj) {
		assertStateOk(theObj);

		try {
			return EmpireUtil.exists(getDataSource(), theObj);
		}
		catch (DataSourceException e) {
			throw new PersistenceException(e);
		}
	}

	/**
	 * @inheritDoc
	 */
	public Query createQuery(final String theQueryString) {
		return getDataSource().getQueryFactory().createQuery(theQueryString);
	}

	/**
	 * @inheritDoc
	 */
	public Query createNamedQuery(final String theName) {
		return getDataSource().getQueryFactory().createNamedQuery(theName);
	}

	/**
	 * @inheritDoc
	 */
	public Query createNativeQuery(final String theQueryString) {
		return getDataSource().getQueryFactory().createNativeQuery(theQueryString);
	}

	/**
	 * @inheritDoc
	 */
	public Query createNativeQuery(final String theQueryString, final Class theResultClass) {
		return getDataSource().getQueryFactory().createNativeQuery(theQueryString, theResultClass);
	}

	/**
	 * @inheritDoc
	 */
	public Query createNativeQuery(final String theQueryString, final String theResultSetMapping) {
		return getDataSource().getQueryFactory().createNativeQuery(theQueryString, theResultSetMapping);
	}

	/**
	 * @inheritDoc
	 */
	public void joinTransaction() {
		assertOpen();

		// TODO: maybe do something?  I don't really understand what this method is supposed to do.  from the javadoc
		// the intent is not clear.  i need to do a little more reading on this.  for now, lets make it fail
		// like a user would expect, but not do anything.  that's not ideal, but we'll eventually sort this out.
	}

	/**
	 * @inheritDoc
	 */
	public Object getDelegate() {
		return mDataSource;
	}

	/**
	 * @inheritDoc
	 */
	public void close() {
		if (!isOpen()) {
			throw new IllegalStateException("EntityManager is already closed.");
		}

		getDataSource().disconnect();

		mIsOpen = false;

		cleanState();
	}

	/**
	 * Clean up the current state of the EntityManager, release attached entities and the like.
	 */
	private void cleanState() {
		mManagedEntityListeners.clear();
	}

	/**
	 * @inheritDoc
	 */
	public boolean isOpen() {
		return mIsOpen;
	}

	/**
	 * @inheritDoc
	 */
	public EntityTransaction getTransaction() {
		if (mTransaction == null) {
			mTransaction = new DataSourceEntityTransaction(mTransactionSource);
		}

		return mTransaction;
	}

	/**
	 * @inheritDoc
	 */
	public void persist(final Object theObj) {
		assertStateOk(theObj);

		try {
			assertNotContains(theObj);
		}
		catch (Throwable e) {
			throw new EntityExistsException(e);
		}

		try {
			prePersist(theObj);

			Graph aData = RdfGenerator.asRdf(theObj);

			if (doesSupportNamedGraphs() && EmpireUtil.hasNamedGraphSpecified(theObj)) {
				asSupportsNamedGraphs().add(EmpireUtil.getNamedGraph(theObj), aData);
			}
			else {
				getDataSource().add(aData);
			}

			if (!contains(theObj)) {
				throw new PersistenceException("Addition failed for object: " + theObj.getClass() + " -> " + EmpireUtil.asSupportsRdfId(theObj).getRdfId());
			}

			cascadeOperation(theObj, new IsPersistCascade(), new MergeCascade());

			postPersist(theObj);
		}
		catch (InvalidRdfException ex) {
			throw new IllegalStateException(ex);
		}
		catch (DataSourceException ex) {
			throw new PersistenceException(ex);
		}
	}

	private MutableDataSource getDataSource() {
		return (MutableDataSource) getDelegate();
	}

	/**
	 * @inheritDoc
	 */
	@SuppressWarnings("unchecked")
	public <T> T merge(final T theT) {
		assertStateOk(theT);

		assertContains(theT);

		try {
			preUpdate(theT);

			Graph aExistingData = EmpireUtil.describe(getDataSource(), theT);
			Graph aData = RdfGenerator.asRdf(theT);

			if (doesSupportNamedGraphs() && EmpireUtil.hasNamedGraphSpecified(theT)) {
				java.net.URI aGraphURI = EmpireUtil.getNamedGraph(theT);

				asSupportsNamedGraphs().remove(aGraphURI, aExistingData);
				asSupportsNamedGraphs().add(aGraphURI, aData);
			}
			else {
				getDataSource().remove(aExistingData);

				getDataSource().add(aData);
			}

			// cascade the merge
			cascadeOperation(theT, new IsMergeCascade(), new MergeCascade());
//			Collection<AccessibleObject> aAccessors = new HashSet<AccessibleObject>();
//			aAccessors.addAll(getAnnotatedFields(theT.getClass()));
//			aAccessors.addAll(getAnnotatedGetters(theT.getClass(), true));
//			for (AccessibleObject aObj : aAccessors) {
//				if (BeanReflectUtil.isMergeCascade(aObj)) {
//
//					try {
//						Object aAccessorValue = BeanReflectUtil.safeGet(aObj, theT);
//
//						// is it the correct JPA behavior to persist a value when it does not exist during a cascaded
//						// merge?  or should that be a PersistenceException just like any normal merge for an un-managed
//						// object?
//						// Futhermore, is it an error if you specify a cascade type for something that cannot be
//						// cascaded?  such as strings, or a non Entity instance?
//						if (Collection.class.isAssignableFrom(aAccessorValue.getClass())) {
//							for (Object aValue : (Collection) aAccessorValue) {
//
//								if (!EmpireUtil.isEmpireCompatible(aAccessorValue.getClass())) {
//									continue;
//								}
//
//								if (contains(aValue)) {
//									merge(aValue);
//								}
//								else {
//									persist(aValue);
//								}
//							}
//						}
//						else {
//							if (EmpireUtil.isEmpireCompatible(aAccessorValue.getClass())) {
//								if (contains(aAccessorValue)) {
//									merge(aAccessorValue);
//								}
//								else {
//									persist(aAccessorValue);
//								}
//							}
//						}
//					}
//					catch (InvocationTargetException e) {
//						e.printStackTrace();
//						throw new PersistenceException(e);
//					}
//				}
//			}

			postUpdate(theT);

            return theT;
		}
		catch (DataSourceException ex) {
			throw new PersistenceException(ex);
		}
		catch (InvalidRdfException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private <T> void cascadeOperation(T theT, CascadeTest theCascadeTest, CascadeAction theAction) {
		Collection<AccessibleObject> aAccessors = new HashSet<AccessibleObject>();
		aAccessors.addAll(getAnnotatedFields(theT.getClass()));
		aAccessors.addAll(getAnnotatedGetters(theT.getClass(), true));
		for (AccessibleObject aObj : aAccessors) {
			if (theCascadeTest.accept(aObj)) {
				try {
					Object aAccessorValue = BeanReflectUtil.safeGet(aObj, theT);
					
					if (aAccessorValue == null) {
						continue;
					}

					theAction.setData(aAccessorValue);
					theAction.execute();
				}
				catch (Exception e) {
					throw new PersistenceException(e);
				}
			}
		}
	}

	private class MergeCascade extends CascadeAction {
		public void cascade(Object theValue) {
			// is it the correct JPA behavior to persist a value when it does not exist during a cascaded
			// merge?  or should that be a PersistenceException just like any normal merge for an un-managed
			// object?
			if (EmpireUtil.isEmpireCompatible(theValue.getClass())) {
				if (contains(theValue)) {
					merge(theValue);
				}
				else {
					persist(theValue);
				}
			}
		}
	}

	private class RemoveCascade extends CascadeAction {
		public void cascade(Object theValue) {
			if (EmpireUtil.isEmpireCompatible(theValue.getClass())) {
				if (contains(theValue)) {
					remove(theValue);
				}
			}
		}
	}

	private class IsMergeCascade extends CascadeTest {
		public boolean accept(final AccessibleObject theValue) {
			return BeanReflectUtil.isMergeCascade(theValue);
		}
	}

	private class IsRemoveCascade extends CascadeTest {
		public boolean accept(final AccessibleObject theValue) {
			return BeanReflectUtil.isRemoveCascade(theValue);
		}
	}

	private class IsPersistCascade extends CascadeTest {
		public boolean accept(final AccessibleObject theValue) {
			return BeanReflectUtil.isPersistCascade(theValue);
		}
	}

	private abstract class CascadeTest implements Predicate<AccessibleObject> {
	}

	private abstract class CascadeAction extends AbstractDataCommand<Object> {
		public abstract void cascade(Object theObj);

		public void execute() {
			// is it an error if you specify a cascade type for something that cannot be
			// cascaded?  such as strings, or a non Entity instance?
			if (Collection.class.isAssignableFrom(getData().getClass())) {
				for (Object aValue : (Collection) getData()) {
					cascade(aValue);
				}
			}
			else {
				cascade(getData());
			}
		}
	}

	/**
	 * @inheritDoc
	 */
	public void remove(final Object theObj) {
		assertStateOk(theObj);

		assertContains(theObj);

		try {
			preRemove(theObj);

			// we were transforming the current object to RDF and deleting that, but i dont think that's the intended
			// behavior.  you want to delete everything about the object in the database, not the properties specifically
			// on the thing being deleted -- there's an obvious case where there could be a delta between them and you
			// don't delete everything.  so we'll do a describe on the object and delete everything we know about it
			// i.e. everything where its in the subject position.

			//Graph aData = RdfGenerator.asRdf(theObj);
			Graph aData = EmpireUtil.describe(getDataSource(), theObj);

			if (doesSupportNamedGraphs() && EmpireUtil.hasNamedGraphSpecified(theObj)) {
				asSupportsNamedGraphs().remove(EmpireUtil.getNamedGraph(theObj), aData);
			}
			else {
				getDataSource().remove(aData);
			}

			if (contains(theObj)) {
				throw new PersistenceException("Remove failed for object: " + theObj.getClass() + " -> " + EmpireUtil.asSupportsRdfId(theObj).getRdfId());
			}

			cascadeOperation(theObj, new IsRemoveCascade(), new RemoveCascade());

			postRemove(theObj);
		}
		catch (DataSourceException ex) {
			throw new PersistenceException(ex);
		}
//		catch (InvalidRdfException ex) {
//			throw new IllegalStateException(ex);
//		}
	}

	/**
	 * @inheritDoc
	 */
	public <T> T find(final Class<T> theClass, final Object theObj) {
		assertOpen();

		try {
			if (EmpireUtil.exists(getDataSource(), EmpireUtil.asPrimaryKey(theObj))) {
				T aT = RdfGenerator.fromRdf(theClass, EmpireUtil.asPrimaryKey(theObj), getDataSource());

				postLoad(aT);

				return aT;
			}
			else {
				return null;
			}
		}
		catch (InvalidRdfException e) {
			throw new IllegalArgumentException("Type is not valid, or object with key is not a valid Rdf Entity.", e);
		}
		catch (DataSourceException e) {
			throw new PersistenceException(e);
		}
	}

	/**
	 * @inheritDoc
	 */
	public <T> T getReference(final Class<T> theClass, final Object theObj) {
		assertOpen();

		T aObj = find(theClass, theObj);

		if (aObj == null) {
			throw new EntityNotFoundException("Cannot find Entity with primary key: " + theObj);
		}

		return aObj;
	}

	/**
	 * Enforce that the object exists in the database
	 * @param theObj the object that should exist
	 * @throws IllegalArgumentException thrown if the object does not exist in the database
	 */
	private void assertContains(Object theObj) {
		if (!contains(theObj)) {
			throw new IllegalArgumentException("Entity does not exist: " + theObj);
		}
	}

	/**
	 * Enforce that the object does not exist in the database
	 * @param theObj the object that should not exist
	 * @throws IllegalArgumentException thrown if the object already exists in the database
	 */
	private void assertNotContains(Object theObj) {
		if (contains(theObj)) {
			throw new IllegalArgumentException("Entity already exists: " + theObj);
		}
	}

	/**
	 * Assert that the state of the EntityManager is ok; that it is open, and the specified object is a valid Rdf entity.
	 * @param theObj the object to check
	 * @throws IllegalStateException if the EntityManager is closed
	 * @throws IllegalArgumentException thrown if the value is not a valid Rdf Entity
	 */
	private void assertStateOk(Object theObj) {
		assertOpen();
		assertSupported(theObj);
	}

	/**
	 * Enforce that the EntityManager is open
	 * @throws IllegalStateException thrown if the EntityManager is closed or not yet open
	 */
	private void assertOpen() {
		if (!isOpen()) {
			throw new IllegalStateException("Cannot perform operation, EntityManager is not open");
		}
	}

	/**
	 * Assert that the object can be supported by this EntityManager, that is it a valid Rdf entity
	 * @param theObj the object to validate
	 * @throws IllegalArgumentException thrown if the object is not a valid Rdf entity.
	 */
	private void assertSupported(Object theObj) {
		if (theObj == null) {
			throw new IllegalArgumentException("null objects are not supported");
		}

		assertEntity(theObj);
		assertRdfClass(theObj);
		if (!(theObj instanceof SupportsRdfId)) {
			throw new IllegalArgumentException("Persistent RDF objects must implement the SupportsRdfId interface.");
		}
	}

	/**
	 * Enforce that the object has the {@link Entity} annotation
	 * @param theObj the instance
	 * @throws IllegalArgumentException if the instances does not have the Entity Annotation
	 * @see Entity
	 */
	private void assertEntity(Object theObj) {
		assertHasAnnotation(theObj, Entity.class);
	}

	/**
	 * Enforce that the object has the {@link com.clarkparsia.empire.annotation.RdfsClass} annotation
	 * @param theObj the instance
	 * @throws IllegalArgumentException if the instances does not have the RdfClass annotation
	 * @see com.clarkparsia.empire.annotation.RdfsClass
	 */
	private void assertRdfClass(Object theObj) {
		assertHasAnnotation(theObj, RdfsClass.class);
	}

	/**
	 * Verify that the instance has the specified annotation
	 * @param theObj the instance
	 * @param theAnnotation the annotation the instance is required to have
	 * @throws IllegalArgumentException thrown if the instance does not have the required annotation
	 */
	private void assertHasAnnotation(Object theObj, Class<? extends Annotation> theAnnotation) {
		if (!hasAnnotation(theObj.getClass(), theAnnotation)) {
			throw new IllegalArgumentException("Object is not an " + theAnnotation.getSimpleName());
		}
	}

	/**
	 * Returns whether or not the data source supports operations on named sub-graphs
	 * @return true if it does, false otherwise.  Returning true indicates calls to {@link #asSupportsNamedGraphs()}
	 * will return successfully without a ClassCastException
	 */
	private boolean doesSupportNamedGraphs() {
		return getDataSource() instanceof SupportsNamedGraphs;
	}

	/**
	 * Returns a reference to an object (the data source) which can perform operations on named sub-graphs
	 * @return the data source as a {@link SupportsNamedGraphs}
	 * @throws ClassCastException thrown if the data source does not implements SupportsNamedGraphs
	 */
	private SupportsNamedGraphs asSupportsNamedGraphs() {
		return (SupportsNamedGraphs) getDataSource();
	}

	/**
	 * Fire the PostPersist lifecycle event for the Entity
	 * @param theObj the Entity to fire the event for
	 */
	private void postPersist(Object theObj) {
		handleLifecycleCallback(theObj, PostPersist.class);
	}

	/**
	 * Fire the PostRemove lifecycle event for the Entity
	 * @param theObj the Entity to fire the event for
	 */
	private void postRemove(Object theObj) {
		handleLifecycleCallback(theObj, PostRemove.class);
	}

	/**
	 * Fire the PostLoad lifecycle event for the Entity
	 * @param theObj the Entity to fire the event for
	 */
	private void postLoad(Object theObj) {
		handleLifecycleCallback(theObj, PostLoad.class);
	}

	/**
	 * Fire the PreRemove lifecycle event for the Entity
	 * @param theObj the Entity to fire the event for
	 */
	private void preRemove(Object theObj) {
		handleLifecycleCallback(theObj, PreRemove.class);
	}

	/**
	 * Fire the PreUpdate lifecycle event for the Entity
	 * @param theObj the Entity to fire the event for
	 */
	private void preUpdate(Object theObj) {
		handleLifecycleCallback(theObj, PreUpdate.class);
	}

	/**
	 * Fire the PostUpdate lifecycle event for the Entity
	 * @param theObj the Entity to fire the event for
	 */
	private void postUpdate(Object theObj) {
		handleLifecycleCallback(theObj, PostUpdate.class);
	}

	/**
	 * Fire the PrePersist lifecycle event for the Entity
	 * @param theObj the entity to fire the event for
	 */
	private void prePersist(Object theObj) {
		handleLifecycleCallback(theObj, PrePersist.class);
	}

	/**
	 * Handle the dispatching of the specified lifecycle event
	 * @param theObj the object involved in the event
	 * @param theLifecycleAnnotation the annotation denoting the event, such as {@link PrePersist}, {@link PostLoad}, etc.
	 */
	private void handleLifecycleCallback(Object theObj, Class<? extends Annotation> theLifecycleAnnotation) {
		if (theObj == null) {
			return;
		}

		Method aPrePersist = getAnnotatedMethod(theObj, theLifecycleAnnotation);

		if (aPrePersist != null) {
			// Entity methods take no arguments...
			try {
				aPrePersist.invoke(theObj);
			}
			catch (Exception e) {
				LOGGER.error("There was an error during entity lifecycle notification for annotation: " +
							 theLifecycleAnnotation + " on object: " + theObj +".", e);
			}
		}

		for (Object aListener : getEntityListeners(theObj)) {
			Method aMethod = getAnnotatedMethod(aListener, theLifecycleAnnotation);

			// EntityListeners methods take a single arguement, the entity
			try {
				aMethod.invoke(aListener, theObj);
			}
			catch (Exception e) {
				LOGGER.error("There was an error during lifecycle notification for annotation: " +
							 theLifecycleAnnotation + " on object: " + theObj + ".", e);
			}
		}
	}

	/**
	 * Get or create the list of EntityListeners for an object.  If a list is created, it will be kept around and
	 * re-used for later persistence operations.
	 * @param theObj the object to get EntityLIsteners for
	 * @return the list of EntityListeners for the object, or null if they do not exist
	 */
	private Collection<Object> getEntityListeners(Object theObj) {
		EntityListeners aEntityListeners = theObj.getClass().getAnnotation(EntityListeners.class);
		Collection<Object> aListeners = mManagedEntityListeners.get(theObj);

		if (aListeners == null) {
			if (aEntityListeners != null) {
				// if there are entity listeners, lets create them
				aListeners = new HashSet<Object>();
				for (Class<?> aClass : aEntityListeners.value()) {
					try {
						aListeners.add(Empire.get().instance(aClass));
					}
					catch (Exception e) {
						LOGGER.error("There was an error instantiating an EntityListener. ", e);
					}
				}

				mManagedEntityListeners.put(theObj, aListeners);
			}
			else {
				aListeners = Collections.emptyList();
			}
		}

		return aListeners;
	}

	/**
	 * Returns a Method on the object with the given annotation
	 * @param theObj the object whose methods should be scanned
	 * @param theAnnotation the annotation to look for
	 * @return a method with the given annotation, or null if one is not found.
	 */
	private Method getAnnotatedMethod(final Object theObj, final Class<? extends Annotation> theAnnotation) {
		// TODO: verify multliple methods don't have the annotation
		for (Method aMethod : theObj.getClass().getMethods()) {
			if (aMethod.getAnnotation(theAnnotation) != null) {
				return aMethod;
			}
		}

		return null;
	}
}
