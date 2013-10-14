/*
 * Polyglotter (http://polyglotter.org)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors.
 *
 * Polyglotter is free software. Unless otherwise indicated, all code in Polyglotter
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * Polyglotter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.modeshape.modeler.internal;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;

import org.jsoup.helper.StringUtil;
import org.modeshape.modeler.Model;
import org.modeshape.modeler.ModelType;
import org.modeshape.modeler.ModelerException;
import org.modeshape.modeler.extensions.Dependency;
import org.modeshape.modeler.extensions.DependencyProcessor;

/**
 * 
 */
public class ModelImpl extends ModelObjectImpl implements Model {

    static final Collection< Dependency > NO_DEPENDENCIES = Collections.emptyList();

    private Collection< Dependency > dependencies;

    /**
     * @param manager
     *        the ModeShapeModeler's manager
     * @param modelPath
     *        a path to a model
     */
    public ModelImpl( final Manager manager,
                      final String modelPath ) {
        super( manager, modelPath, -1 );
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.modeshape.modeler.Model#allDependenciesExist()
     */
    @Override
    public boolean allDependenciesExist() throws ModelerException {
        return missingDependencies().isEmpty();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.modeshape.modeler.Model#dependencies()
     */
    @Override
    public Collection< Dependency > dependencies() throws ModelerException {
        if ( this.dependencies == null ) {
            this.dependencies = manager.run( new Task< Collection< Dependency > >() {

                @Override
                public Collection< Dependency > run( final Session session ) throws Exception {
                    final Node modelNode = session.getNode( absolutePath() );

                    if ( modelNode.hasNode( ModelerLexicon.DEPENDENCIES_NODE ) ) {
                        final NodeIterator itr = modelNode.getNode( ModelerLexicon.DEPENDENCIES_NODE ).getNodes();
                        final Collection< Dependency > result = new ArrayList<>( ( int ) itr.getSize() );

                        while ( itr.hasNext() ) {
                            final Node dependencyNode = itr.nextNode();
                            String dependencyPath = null;
                            boolean exists = false;

                            if ( dependencyNode.hasProperty( ModelerLexicon.PATH_PROPERTY ) ) {
                                dependencyPath = dependencyNode.getProperty( ModelerLexicon.PATH_PROPERTY ).getString();
                            }

                            if ( !StringUtil.isBlank( dependencyPath ) ) {
                                exists = session.nodeExists( dependencyPath );
                            }

                            final Dependency dependency = new Dependency( dependencyPath, exists );

                            if ( dependencyNode.hasProperty( ModelerLexicon.SOURCE_REFERENCE_PROPERTY ) ) {
                                for ( final Value value : dependencyNode.getProperty( ModelerLexicon.SOURCE_REFERENCE_PROPERTY ).getValues() ) {
                                    dependency.addSourceReference( value.getString() );
                                }
                            }

                            result.add( dependency );
                        }

                        return result;
                    }

                    return NO_DEPENDENCIES;
                }
            } );
        }

        return this.dependencies;
    }

    /**
     * @return the dependency processor or <code>null</code> if one does not exist
     * @throws ModelerException
     *         if there is an error constructing the dependency processor
     */
    public DependencyProcessor dependencyProcessor() throws ModelerException {
        return ( ( ModelTypeImpl ) modelType() ).dependencyProcessor();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.modeshape.modeler.Model#externalLocation()
     */
    @Override
    public URL externalLocation() throws ModelerException {
        return manager.run( new Task< URL >() {

            @Override
            public URL run( final Session session ) throws Exception {
                final Node model = session.getNode( path );
                return model.hasProperty( ModelerLexicon.EXTERNAL_LOCATION ) ? new URL( model.getProperty( ModelerLexicon.EXTERNAL_LOCATION ).getString() )
                                                                            : null;
            }
        } );
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.modeshape.modeler.Model#missingDependencies()
     */
    @Override
    public Collection< Dependency > missingDependencies() throws ModelerException {
        return manager.run( new Task< Collection< Dependency > >() {

            @Override
            public Collection< Dependency > run( final Session session ) throws Exception {
                final Collection< Dependency > dependencies = dependencies();

                if ( dependencies.isEmpty() ) {
                    return NO_DEPENDENCIES;
                }

                final Collection< Dependency > missing = new ArrayList<>();

                for ( final Dependency dependency : dependencies ) {
                    final String path = dependency.path();

                    if ( !session.nodeExists( path ) ) {
                        missing.add( dependency );
                    } else {
                        missingDependencies( dependency, missing, session );
                    }
                }

                return missing;
            }
        } );
    }

    void missingDependencies( final Dependency dependency,
                              final Collection< Dependency > missing,
                              final Session session ) throws Exception {
        if ( dependency.exists() ) {
            // find all models and all their dependencies
            final Node node = session.getNode( dependency.path() );
            final NodeIterator itr = node.getNodes();

            while ( itr.hasNext() ) {
                final Node kid = itr.nextNode();

                if ( modelNode( kid ) ) {
                    final Model dependencyModel = new ModelImpl( this.manager, dependency.path() );
                    missing.addAll( dependencyModel.missingDependencies() );
                }
            }
        } else {
            missing.add( dependency );
        }
    }

    private boolean modelNode( final Node node ) throws Exception {
        assert ( node != null );

        for ( final NodeType nodeType : node.getMixinNodeTypes() ) {
            if ( ModelerLexicon.MODEL_MIXIN.equals( nodeType.getName() ) ) {
                return true;
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.modeshape.modeler.Model#modelType()
     */
    @Override
    public ModelType modelType() throws ModelerException {
        return manager.run( new Task< ModelType >() {

            @Override
            public ModelType run( final Session session ) throws Exception {
                return manager.modelTypeManager().modelType( session.getNode( path )
                                                                    .getProperty( ModelerLexicon.MODEL_TYPE )
                                                                    .getString() );
            }
        } );
    }

}
