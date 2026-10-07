package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.SubscriptionManagerNode;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.service.ServiceDependency;
import com.telamin.fluxtion.runtime.service.ServiceRegistryQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Finds the service dependencies a processor declares that none of the services it was given can satisfy
 * (fluxtionauditlog-analyser#126).
 * <p>
 * A {@code @ServiceRegistered} callback fires only when a registered service matches it exactly, by the rule in the
 * runtime's {@code ServiceRegistryNode}: the service's declared class must <em>equal</em> the callback's parameter type,
 * and, for a one-argument callback, the service's name must equal the annotation value — or, when the annotation names
 * nothing, the parameter type's canonical class name. A Mongoose service is named by its yml {@code name}, else its
 * class name, so a binding that expects one name and a service registered under another never meet, and nothing says
 * so. This check states it, once per unmet dependency, as a warning; it never refuses the processor.
 * <p>
 * Two-argument callbacks ({@code (service, String name)}) declare no name: any service of the exact type reaches them,
 * so they are unmet only when no service of that type is registered. The runtime's own
 * {@link SubscriptionManagerNode}, which every processor carries, listens for feeds that way whether or not the
 * processor uses any; it is left out, or every processor on a server without feeds would warn.
 */
final class UnboundServiceCheck {

    private UnboundServiceCheck() {
    }

    /**
     * @param processorName the processor's registered name
     * @param flow          the processor
     * @param registered    every service registered into the processor, as it was registered
     * @return one warning per declared dependency that no service in {@code registered} satisfies; empty when the
     * processor exposes no service registry
     */
    static List<String> warnings(String processorName, DataFlow flow, Collection<Service<?>> registered) {
        Optional<ServiceRegistryQuery> query = flow.serviceRegistryQuery();
        if (query.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (ServiceDependency dependency : query.get().serviceDependencies()) {
            List<String> consumers = consumerNames(dependency);
            if (consumers.isEmpty() || satisfied(dependency, registered)) {
                continue;
            }
            out.add(describe(processorName, dependency, consumers, registered));
        }
        return out;
    }

    /** The runtime's matching rule (ServiceRegistryNode.registerService): exact class, and exact name when one is declared. */
    static boolean satisfied(ServiceDependency dependency, Collection<Service<?>> registered) {
        for (Service<?> service : registered) {
            if (dependency.serviceClass().equals(service.serviceClass())
                    && (dependency.serviceName() == null || dependency.serviceName().equals(service.serviceName()))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> consumerNames(ServiceDependency dependency) {
        List<String> names = new ArrayList<>();
        for (ServiceDependency.ConsumerNode consumer : dependency.consumers()) {
            if (dependency.serviceName() == null && consumer.node() instanceof SubscriptionManagerNode) {
                continue;
            }
            names.add(consumer.nodeName() == null ? consumer.node().getClass().getName() : consumer.nodeName());
        }
        return names;
    }

    private static String describe(String processorName, ServiceDependency dependency, List<String> consumers,
                                   Collection<Service<?>> registered) {
        String type = dependency.serviceClass().getCanonicalName() == null
                ? dependency.serviceClass().getName() : dependency.serviceClass().getCanonicalName();
        String expected = dependency.serviceName();
        TreeSet<String> sameType = new TreeSet<>();
        TreeSet<String> sameNameOtherType = new TreeSet<>();
        TreeSet<String> all = new TreeSet<>();
        for (Service<?> service : registered) {
            all.add(service.serviceName());
            if (dependency.serviceClass().equals(service.serviceClass())) {
                sameType.add(service.serviceName());
            } else if (Objects.equals(expected, service.serviceName())) {
                sameNameOtherType.add(service.serviceClass().getName());
            }
        }
        StringBuilder text = new StringBuilder("processor '").append(processorName).append("': node ")
                .append(consumers.size() == 1 ? "'" + consumers.get(0) + "'" : consumers.toString())
                .append(" expects service ").append(type);
        if (expected == null) {
            text.append(" (any name)");
        } else {
            text.append(" named '").append(expected).append("'");
            if (expected.equals(dependency.serviceClass().getCanonicalName())) {
                text.append(" (an unnamed @ServiceRegistered binds by the class name)");
            }
        }
        text.append(", but no registered service matches, so its @ServiceRegistered callback will never fire. ");
        if (!sameType.isEmpty()) {
            text.append("Registered as ").append(type).append(": ").append(sameType)
                    .append(" — name the binding to match, or register the service as '").append(expected).append("'.");
        } else if (!sameNameOtherType.isEmpty()) {
            text.append("A service named '").append(expected).append("' is registered as type ").append(sameNameOtherType)
                    .append(" — a binding matches the declared service class exactly; declare it as ").append(type).append('.');
        } else {
            text.append("No service of that type is registered; registered services: ").append(all).append('.');
        }
        return text.toString();
    }
}
