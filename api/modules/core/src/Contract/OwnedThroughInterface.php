<?php

namespace Maggie\Core\Contract;

/**
 * For entities that are owned by a user indirectly through a parent entity.
 * The parent entity must implement OwnedByUserInterface.
 */
interface OwnedThroughInterface
{
    /**
     * Returns the relation name to the parent entity that owns this entity.
     * e.g., 'agenda' for an Event that belongs to an Agenda which has a user.
     */
    public static function getOwnerRelation(): string;
}
