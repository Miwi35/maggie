<?php

declare(strict_types=1);

namespace Maggie\Core\Projection;

/**
 * What one flush or one handler did to one row, in the words the projection needs.
 *
 * A removed row is gone by the time the handler returns, so everything its
 * projection needs (owner, indices) is read while it still exists.
 */
final class Change
{
    /**
     * @param class-string             $class         the real class, never a Doctrine proxy's
     * @param list<string>             $properties    the Doctrine properties that changed; empty: unknown, publish everything
     * @param list<string>             $indices       the indices a removed row leaves
     * @param array<string,mixed>|null $actionPayload replaces the entity's own payload
     */
    public function __construct(
        public readonly string $class,
        public readonly string $id,
        public ChangeKind $kind,
        public ?string $ownerId,
        public ?object $entity = null,
        public array $properties = [],
        public array $indices = [],
        public ?array $actionPayload = null,
    ) {
    }

    public function key(): string
    {
        return $this->class.'#'.$this->id;
    }

    /** Folds a later change to the same row into this one. */
    public function absorb(self $later): void
    {
        // A row that is gone stays gone, unless the same id is inserted again.
        if (ChangeKind::Deleted === $this->kind && ChangeKind::Updated === $later->kind) {
            return;
        }

        if (ChangeKind::Deleted === $later->kind || ChangeKind::Deleted === $this->kind) {
            $this->kind = $later->kind;
            $this->entity = $later->entity;
            $this->indices = $later->indices;
            $this->properties = $later->properties;
            $this->actionPayload = $later->actionPayload;
            $this->ownerId = $later->ownerId ?? $this->ownerId;

            return;
        }

        if (ChangeKind::Inserted === $later->kind) {
            $this->kind = ChangeKind::Inserted;
        }

        $this->properties = [] === $this->properties
            ? $later->properties
            : ([] === $later->properties ? $this->properties : array_values(array_unique([...$this->properties, ...$later->properties])));
        $this->entity = $later->entity ?? $this->entity;
        $this->ownerId = $this->ownerId ?? $later->ownerId;
        $this->actionPayload = $later->actionPayload ?? $this->actionPayload;
    }
}
