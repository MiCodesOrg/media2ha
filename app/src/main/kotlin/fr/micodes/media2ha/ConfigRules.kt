package fr.micodes.media2ha

/**
 * Pure decisions about the device identity. Kept out of the UI and the config store so
 * the rename rules (the ones that broke media reporting) are unit-testable.
 */
object ConfigRules {

    /**
     * The device id to keep for the current name: an id already in use stays as it is, and
     * a new one is only derived while the field is still empty. Renaming must not create a
     * second entity with a fresh id.
     */
    fun deviceIdForName(currentId: String, suggestedId: String): String = currentId.ifBlank { suggestedId }

    /**
     * The id to clear when the identity does change, or `null` when it is stable (or was
     * never set): retiring a still-in-use id would remove the live entity.
     */
    fun retiredDeviceId(previousId: String, newId: String): String? =
        previousId.trim().takeIf { it.isNotBlank() && it != newId }
}
