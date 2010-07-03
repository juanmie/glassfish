/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2009-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.osgijavaeebase;

import org.glassfish.api.event.EventListener;
import org.glassfish.api.event.EventTypes;
import org.glassfish.api.event.Events;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.jvnet.hk2.component.Habitat;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.enterprise.module.bootstrap.ModuleStartup;

/**
 * It is responsible for starting any registered {@link Extender} service
 * after GlassFish server is started and stopping them when server is shutdown.
 * We use GlassFish STARTED event to be notified of server startup and shutdown.
 * Because the order in which bundles are started is undefined, we can't just assume existence
 * of Habitat to ask for the {@link }Events} service. Fortunately, HK2 OSGi bundle registers
 * Habitat in service registry. So we track that service and from there we listen to GF events.
 *
 * @author Sanjeeb.Sahoo@Sun.COM
 */
public class ExtenderManager
{
    private static final Logger logger =
            Logger.getLogger(ExtenderManager.class.getPackage().getName());
    private BundleContext context;
    private Habitat habitat; // handle to HK2 service registry
    private Events events;
    private EventListener listener;
    private ServiceTracker extenderTracker;
    private GlassFishServerTracker glassFishServerTracker; // used to track starting of GlassFish

    public ExtenderManager(BundleContext context)
    {
        this.context = context;
    }

    public void start() throws Exception
    {
        glassFishServerTracker = new GlassFishServerTracker(context);
        glassFishServerTracker.open();
    }

    public void stop() throws Exception
    {
        unregisterGlassFishShutdownHook();
        if (glassFishServerTracker != null) {
            glassFishServerTracker.close();
            glassFishServerTracker = null;
        }
        if (extenderTracker != null) {
            extenderTracker.close();
            extenderTracker = null;
        }
        stopExtenders();
    }

    public void startExtenders() {
        extenderTracker = new ExtenderTracker(context);
        extenderTracker.open();
    }

    private void stopExtenders()
    {
        try
        {
            final ServiceReference[] refs = context.getServiceReferences(Extender.class.getName(), null);
            if (refs != null) {
                for (ServiceReference ref : refs) {
                    Extender e = Extender.class.cast(context.getService(ref));
                    try {
                        e.stop();
                    } finally {
                        context.ungetService(ref);
                    }
                }
            }
        }
        catch (InvalidSyntaxException e)
        {
            logger.logp(Level.WARNING, "ExtenderManager", "stopExtenders",
                    "Not able to stop all extenders", e);
        }

    }

    private void unregisterGlassFishShutdownHook() {
        if (listener != null) {
            events.unregister(listener);
        }
    }

    private class ExtenderTracker extends ServiceTracker {
        ExtenderTracker(BundleContext context)
        {
            super(context, Extender.class.getName(), null);
        }

        @Override
        public Object addingService(ServiceReference reference)
        {
            Extender e = Extender.class.cast(context.getService(reference));
            e.start();
            return e;
        }

        @Override
        public void removedService(ServiceReference reference, Object service) {
            Extender e = Extender.class.cast(context.getService(reference));
            e.stop();
        }
    }

    /**
     * Tracks Habitat and obtains EVents service from it and registers a listener
     * that takes care of actually starting and stopping other extenders.
     */
    private class GlassFishServerTracker extends ServiceTracker {
        public GlassFishServerTracker(BundleContext context)
        {
            super(context, Habitat.class.getName(), null);
        }

        @Override
        public Object addingService(ServiceReference reference)
        {
            logger.logp(Level.FINE, "ExtenderManager$GlassFishServerTracker", "addingService", "Habitat has been created");
            ServiceReference habitatServiceRef = context.getServiceReference(Habitat.class.getName());
            habitat = Habitat.class.cast(context.getService(habitatServiceRef));
            events = habitat.getComponent(Events.class);
            listener = new EventListener() {
                public void event(Event event)
                {
                    if (EventTypes.SERVER_READY.equals(event.type())) {
                        startExtenders();
                    } else if (EventTypes.PREPARE_SHUTDOWN.equals(event.type())) {
                        stopExtenders();
                    }
                }
            };
            events.register(listener);
            close(); // no need to track any more
            return super.addingService(reference);
        }
    }
}
