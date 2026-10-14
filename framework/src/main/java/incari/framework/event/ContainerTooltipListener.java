package incari.framework.event;

/** Receives {@link Events#CONTAINER_TOOLTIP}. */
@FunctionalInterface
public interface ContainerTooltipListener {

    /** A container item's tooltip is being made. */
    void onContainerTooltip(ContainerTooltip tooltip);
}
