<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Elasticsearch\SearchDocumentValue;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Enum\QualificationKind;
use Symfony\Component\Serializer\Attribute\Groups;
use Symfony\Component\Serializer\Attribute\Ignore;

/**
 * What an operation says about the transactions it recognises — its category,
 * whether they are mandatory, what kind of movement they are — copied whole
 * onto each of them (spec « Opération et transaction »). The direction is not
 * in it: it is the sign of the amount.
 *
 * Embedded as is in `Transaction`, `RecurringOperation` and
 * `CategorizationRule`. A new parameter is one field here plus its migration:
 * it then exists on the three entities, is copied by `Transaction::qualify()`,
 * serialized, published and offered to the MCP tools — `QualificationContractTest`
 * fails until it is.
 *
 * The category is the one part Doctrine cannot embed (an embeddable holds no
 * association): it stays the `category` column of each entity, which puts it
 * back into the object it hands out ({@see self::withCategory()}).
 */
#[ORM\Embeddable]
final class Qualification implements SearchDocumentValue
{
    /** The Elasticsearch mapping of the object field each entity indexes it as. */
    public const array SEARCH_MAPPING = [
        'mandatory' => ['type' => 'boolean'],
        'kind' => ['type' => 'keyword'],
    ];

    // Both parameters are required on the way in: a qualification is copied
    // whole, so a body that names one and not the other is refused (400)
    // rather than completed with a default.
    public function __construct(
        /** An obligation the owner cannot cut: rent, a loan, insurance. */
        #[ORM\Column(type: 'boolean', options: ['default' => false])]
        #[Groups(['qualification'])]
        private bool $mandatory,
        /** What kind of movement it is; the direction is the sign of the amount. */
        #[ORM\Column(length: 20, enumType: QualificationKind::class, options: ['default' => 'none'])]
        #[Groups(['qualification'])]
        private QualificationKind $kind,
        #[Ignore]
        private ?Category $category = null,
    ) {
    }

    public static function none(?Category $category = null): self
    {
        return new self(false, QualificationKind::None, $category);
    }

    /**
     * What the category's flag meant, until part 4 takes the flag away: the
     * table of the data migration (MAG-476), and what a category set without
     * a qualification of its own carries meanwhile.
     */
    public static function fromObligation(ObligationFlag $flag, ?Category $category = null): self
    {
        return match ($flag) {
            ObligationFlag::Mandatory => new self(true, QualificationKind::None, $category),
            ObligationFlag::Optional, ObligationFlag::Income => new self(false, QualificationKind::None, $category),
            ObligationFlag::Saving => new self(false, QualificationKind::Saving, $category),
            ObligationFlag::Investment => new self(false, QualificationKind::Investment, $category),
            ObligationFlag::Debt => new self(true, QualificationKind::Repayment, $category),
        };
    }

    public static function fromCategory(Category $category): self
    {
        return self::fromObligation($category->getObligation(), $category);
    }

    /**
     * Rebuilds the parameters from their serialized form (`toArray()`), the
     * category given apart.
     *
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data, ?Category $category = null): self
    {
        $kind = $data['kind'] ?? QualificationKind::None;

        return new self(
            (bool) ($data['mandatory'] ?? false),
            $kind instanceof QualificationKind ? $kind : QualificationKind::from((string) $kind),
            $category,
        );
    }

    public static function fromSearchDocument(array $document): static
    {
        return self::fromArray($document);
    }

    /**
     * The names of the parameters, as the columns, the payloads and the MCP
     * tools spell them. Read off the mapping, so a new field is in it at once.
     *
     * @return list<string>
     */
    public static function fields(): array
    {
        $fields = [];
        foreach ((new \ReflectionClass(self::class))->getProperties() as $property) {
            if ([] !== $property->getAttributes(ORM\Column::class)) {
                $fields[] = $property->getName();
            }
        }

        return $fields;
    }

    /**
     * Doctrine names a changed embedded field `qualification.kind`; the
     * payloads carry the object whole under `qualification`.
     *
     * @return array<string, string>
     */
    public static function changedPropertyMap(string $property = 'qualification'): array
    {
        $map = [];
        foreach (self::fields() as $field) {
            $map[$property.'.'.$field] = $property;
        }

        return $map;
    }

    #[Ignore]
    public function getCategory(): ?Category
    {
        return $this->category;
    }

    public function isMandatory(): bool
    {
        return $this->mandatory;
    }

    public function getKind(): QualificationKind
    {
        return $this->kind;
    }

    /** The same parameters under another category, or none. */
    public function withCategory(?Category $category): self
    {
        $copy = clone $this;
        $copy->category = $category;

        return $copy;
    }

    /** Whether it says nothing at all: no category, and every parameter at its default. */
    public function carriesNothing(): bool
    {
        return null === $this->category && $this->toArray() === self::none()->toArray();
    }

    public function equals(self $other): bool
    {
        return $this->toArray() === $other->toArray()
            && (string) $this->category?->getId() === (string) $other->category?->getId();
    }

    /**
     * The parameters as every channel spells them — Mercure, Elasticsearch,
     * the MCP tools. The category is not in it: each entity serializes it as
     * its own `categoryId`.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $values = [];
        foreach (self::fields() as $field) {
            $value = $this->{$field};
            $values[$field] = $value instanceof \BackedEnum ? $value->value : $value;
        }

        return $values;
    }
}
