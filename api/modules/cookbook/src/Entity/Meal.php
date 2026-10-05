<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Entity\Event;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\State\CreateMealProcessor;
use Maggie\Cookbook\State\DeleteMealProcessor;
use Maggie\Cookbook\State\UpdateMealProcessor;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Serializer\StrictDayNormalizer;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Validator\Constraints as Assert;

// A meal is a day and a slot — never an instant (MAG-251).
//
// It stays an `Event` so the agenda can show it, but the inherited `startAt`
// and `endAt` are **derived** from `date`: the whole day, in the meal's own
// time zone. No client sends them any more — the two write operations drop
// them from the body.
//
// The owner's decision is that the time does not count, and a derived instant
// cannot drift from the day the way a client-supplied one did: the week view
// sent midnight at a hard-coded `+01:00`, an hour short for eight months of
// the year, and every reader that took the day from the stored timestamp
// ranged the meal on the day before.
//
// Comments and not a docblock on purpose: API Platform publishes a class
// docblock as the resource description, and the inside of a fix is not the
// public documentation of a resource.
#[ORM\Entity(repositoryClass: MealRepository::class)]
#[ORM\Index(columns: ['date'], name: 'idx_meal_date')]
#[ApiFilter(DateFilter::class, properties: ['date'])]
#[ApiFilter(OrderFilter::class, properties: ['date'])]
#[Indexed(index: 'meals', module: 'cookbook')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateMealProcessor::class, denormalizationContext: self::WRITE_CONTEXT),
    new Patch(processor: UpdateMealProcessor::class, denormalizationContext: self::WRITE_CONTEXT),
    new Delete(processor: DeleteMealProcessor::class),
])]
class Meal extends Event implements MercurePublishable
{
    /**
     * The instants are derived, so a body that carries them is not a request to
     * move the meal — it is the old client, and honouring it is the bug. PATCH
     * denormalizes straight into the managed entity, which is where an instant
     * a client still sends would otherwise reach the database.
     *
     * @var array<string, list<string>>
     */
    private const WRITE_CONTEXT = ['ignored_attributes' => ['startAt', 'endAt', 'originalStartAt']];

    // The day the meal is eaten, as a day: `DATE` in the database, `Y-m-d` on
    // the wire. The serializer format is pinned on both sides — without it a
    // `DateTimeImmutable` normalizes to an instant, which is the shape this
    // field exists to get rid of. `!Y-m-d` on the way in makes the
    // denormalizer strict: it zeroes the time instead of filling it with the
    // current one, and StrictDayNormalizer refuses anything that is not
    // exactly a day — a 400, see MealApiTest.
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    #[Assert\NotNull(message: 'A meal needs a day.')]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(
        description: 'The day the meal is eaten, YYYY-MM-DD. A meal has no time.',
        openapiContext: ['type' => 'string', 'format' => 'date', 'example' => '2026-10-07'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $date = null;

    #[ORM\Column(length: 10, enumType: MealSlot::class)]
    #[IndexedField(type: 'keyword')]
    private MealSlot $slot;

    /** @var Collection<int, Recipe> */
    #[ORM\ManyToMany(targetEntity: Recipe::class)]
    #[ORM\JoinTable(name: 'meal_recipe')]
    private Collection $recipes;

    public function __construct()
    {
        parent::__construct();
        $this->recipes = new ArrayCollection();
    }

    /**
     * A day from the `Y-m-d` the MCP tool and the message commands carry.
     *
     * Strict on purpose: `new \DateTimeImmutable('2026-13-45')` throws, but
     * `'2026-10-07 19:30'` and `'next tuesday'` do not, and either would put a
     * meal somewhere nobody asked for. Nothing but a day gets through here.
     *
     * @throws \DomainException when the string is not a day
     */
    public static function dayFromString(string $day): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day, new \DateTimeZone('UTC'));
        $errors = \DateTimeImmutable::getLastErrors();

        if (false === $parsed || (false !== $errors && ($errors['warning_count'] > 0 || $errors['error_count'] > 0))) {
            throw new \DomainException("Not a day: \"{$day}\". A meal's date is YYYY-MM-DD, with no time.");
        }

        return $parsed;
    }

    public function getDate(): ?\DateTimeImmutable
    {
        return $this->date;
    }

    /**
     * Sets the day, and with it the instants the agenda reads.
     *
     * The whole day in the meal's time zone is the simplest shape that keeps a
     * meal showing as an all-day entry in the agenda: the slot already says
     * lunch or dinner, so an hour would add nothing and bring the drift back.
     *
     * Only the day of `$date` is read, and what is stored carries no offset of
     * its own — a `DATE` column holds a day, and an instant handed in here
     * cannot push the meal onto another one.
     */
    public function setDate(\DateTimeImmutable $date): static
    {
        $day = $date->format('Y-m-d');

        $this->date = new \DateTimeImmutable($day, new \DateTimeZone('UTC'));

        $wholeDay = new \DateTimeImmutable($day.' 00:00:00', new \DateTimeZone($this->getTimeZone()));
        parent::setStartAt($wholeDay);
        parent::setEndAt($wholeDay->setTime(23, 59, 59));
        $this->setAllDay(true);

        return $this;
    }

    public function setTimeZone(string $timeZone): static
    {
        // Re-derives the instants: moving the time zone moves the day's
        // bounds. A comment, not a docblock — API Platform would publish it as
        // the description of the inherited `timeZone` property.
        parent::setTimeZone($timeZone);

        if (null !== $this->date) {
            $this->setDate($this->date);
        }

        return $this;
    }

    public function getSlot(): MealSlot
    {
        return $this->slot;
    }

    public function setSlot(MealSlot $slot): static
    {
        $this->slot = $slot;

        return $this;
    }

    /** @return Collection<int, Recipe> */
    public function getRecipes(): Collection
    {
        return $this->recipes;
    }

    public function addRecipe(Recipe $recipe): static
    {
        if (!$this->recipes->contains($recipe)) {
            $this->recipes->add($recipe);
        }

        return $this;
    }

    public function removeRecipe(Recipe $recipe): static
    {
        $this->recipes->removeElement($recipe);

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        $doc = parent::toSearchDocument();
        $doc['date'] = $this->date?->format('Y-m-d');
        $doc['slot'] = $this->slot->value;
        $doc['recipeIds'] = $this->recipes->map(fn (Recipe $r) => (string) $r->getId())->toArray();

        return $doc;
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        // The day, not the instants: a screen reading this payload has to put
        // the meal where the API would.
        return self::filterPayload([
            'summary' => $this->getSummary(),
            'date' => $this->date?->format('Y-m-d'),
            'slot' => $this->slot->value,
            'recipes' => $this->recipes->map(fn (Recipe $r) => [
                'id' => (string) $r->getId(),
                'name' => $r->getName(),
            ])->toArray(),
        ], $changedProperties);
    }
}
