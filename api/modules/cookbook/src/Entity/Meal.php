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
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Validator\Constraints as Assert;

// A meal is a day and a slot — never an instant (MAG-251).
//
// It stays an `Event` so the agenda can show it, as an all-day one: the
// inherited `startDate` and `endDate` are **derived** from `date`, and its
// `startAt` and `endAt` are null like any all-day event's (MAG-382). No client
// sends them any more — the two write operations drop them from the body.
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
     * The agenda is not the client's to choose either: a meal always goes in
     * the meals' module agenda, and a client that still names one is ignored
     * (MAG-324).
     *
     * @var array<string, list<string>>
     */
    private const WRITE_CONTEXT = ['ignored_attributes' => ['startAt', 'endAt', 'startDate', 'endDate', 'originalStartAt', 'agenda']];

    /** What `Event::$timeZone` defaults to, and what an unresolvable one falls back on. */
    private const DEFAULT_TIME_ZONE = 'Europe/Paris';

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

    // When the owner chose which ingredients go on the grocery list (MAG-295).
    // Null: the meal still feeds the list from its recipes, as before. Set:
    // the list holds what was chosen, in packagings, and editing the meal or
    // its recipes no longer derives anything. Set by the choice endpoint only,
    // never by a client — a client stamping it would switch the derivation
    // off without choosing anything.
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    #[ApiProperty(writable: false)]
    private ?\DateTimeImmutable $groceryChoiceMadeAt = null;

    public function __construct()
    {
        parent::__construct();
        $this->recipes = new ArrayCollection();
    }

    /**
     * A day from the `Y-m-d` the MCP tool and the message commands carry.
     *
     * The same round trip {@see StrictDayNormalizer} applies to the API, so one
     * string is a day or is not a day whichever door it comes through: what came
     * out, formatted back, has to be what came in. `createFromFormat` is lenient
     * where it matters — `'2026-13-45'` rolls over to February 2027 rather than
     * failing, and `'2026-10-7'` parses — and either would put a meal on a day
     * nobody named.
     *
     * @throws \DomainException when the string is not a day
     */
    public static function dayFromString(string $day): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day, new \DateTimeZone('UTC'));

        if (false === $parsed || $parsed->format('Y-m-d') !== $day) {
            throw new \DomainException("Not a day: \"{$day}\". A meal's date is YYYY-MM-DD, with no time.");
        }

        return $parsed;
    }

    /**
     * The day a *range* end falls on, read in Paris — a bare day, but also a
     * full timestamp, of which only the day is kept.
     *
     * Lenient where {@see dayFromString} is strict, and the asymmetry is the
     * point: a range is a window to read, and the model does send timestamps,
     * so answering the week it meant beats answering an error. Writing a meal
     * on a day nobody named is the mistake worth refusing; reading one is not.
     *
     * @throws \DomainException when the string is not a date at all
     */
    public static function dayOfString(string $date): \DateTimeImmutable
    {
        // `new \DateTimeImmutable('')` is *now*, not an error, so an empty
        // range end would silently mean today.
        if ('' === trim($date)) {
            throw new \DomainException('Not a date: "". Use YYYY-MM-DD.');
        }

        $paris = new \DateTimeZone(self::DEFAULT_TIME_ZONE);

        try {
            $read = new \DateTimeImmutable($date, $paris);
        } catch (\Exception $e) {
            throw new \DomainException("Not a date: \"{$date}\". Use YYYY-MM-DD.", 0, $e);
        }

        // `setTimezone` and not only the constructor's zone: a string that
        // carries its own offset ignores that argument, so `2026-10-06T22:30Z`
        // would read as the 6th where in Paris it is already the 7th — the day
        // the asker meant.
        return new \DateTimeImmutable($read->setTimezone($paris)->format('Y-m-d'), new \DateTimeZone('UTC'));
    }

    #[Ignore]
    public function isAgendaChosenByServer(): bool
    {
        return true;
    }

    public function getDate(): ?\DateTimeImmutable
    {
        return $this->date;
    }

    /**
     * Sets the day, and with it the days the agenda reads: the meal is an
     * all-day event on that one day, with no instant (MAG-382).
     *
     * Only the day of `$date` is read: a `DATE` column holds a day, and an
     * instant handed in here cannot push the meal onto another one.
     */
    public function setDate(\DateTimeImmutable $date): static
    {
        $this->date = new \DateTimeImmutable($date->format('Y-m-d'), new \DateTimeZone('UTC'));

        parent::setStartAt(null);
        parent::setEndAt(null);
        parent::setStartDate($this->date);
        parent::setEndDate($this->date);
        parent::setAllDay(true);

        return $this;
    }

    public function setStartAt(?\DateTimeImmutable $startAt): static
    {
        // An instant handed to a meal names a day, and moves it to that day.
        //
        // `Meal` shares its primary key with `event`, so an `Event` write
        // reaches a meal: `EventRepository::find()` returns one for a meal's
        // id, and `findByDateRange()` hands Maggie a meal among the events, id
        // and all. The `update_event` tool and `PATCH /api/events/{id}` then
        // call this. Left inherited, they would set an instant on what is a
        // day, and leave `date` — the field the API, Elasticsearch, Mercure
        // and every week view read — behind.
        //
        // The day is read in the meal's own time zone: an instant at 23:00 UTC
        // is the next day in Paris, and that is the day the writer meant.
        //
        // Comments, not a docblock: API Platform publishes a setter's docblock
        // as the description of the property it writes.
        if (null === $startAt) {
            return parent::setStartAt(null);
        }

        return $this->setDate($startAt->setTimezone($this->zone()));
    }

    public function setEndAt(?\DateTimeImmutable $endAt): static
    {
        // A meal ends when its day does, so there is no end to set. Not a move
        // either: `scheduleTimed()` sets `startAt` and then `endAt`, and a span
        // whose two ends fall on different days would otherwise leave the meal
        // on the last one. `setStartAt()` above is the one that names the day.
        return parent::setEndAt(null);
    }

    public function setStartDate(?\DateTimeImmutable $startDate): static
    {
        // The `Event` door's way of naming the day: it moves the meal. A null
        // is the other schedule clearing the days, and the meal keeps its own.
        if (null === $startDate) {
            return null === $this->date ? parent::setStartDate(null) : $this;
        }

        return $this->setDate($startDate);
    }

    public function setEndDate(?\DateTimeImmutable $endDate): static
    {
        // A meal is one day: its last day is its first.
        return parent::setEndDate($this->date);
    }

    public function setAllDay(bool $allDay): static
    {
        // A meal covers its day, always — `update_event allDay=false` on a
        // meal would otherwise leave the flag off next to a day and no hour.
        //
        // Comments, not a docblock: API Platform publishes a setter's docblock
        // as the description of the property it writes.
        return parent::setAllDay(true);
    }

    /**
     * The meal's time zone, falling back on the column's default — only to
     * read the day an instant handed to {@see setStartAt()} falls on.
     */
    private function zone(): \DateTimeZone
    {
        try {
            return new \DateTimeZone($this->getTimeZone());
        } catch (\Exception) {
            return new \DateTimeZone(self::DEFAULT_TIME_ZONE);
        }
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

    public function getGroceryChoiceMadeAt(): ?\DateTimeImmutable
    {
        return $this->groceryChoiceMadeAt;
    }

    public function setGroceryChoiceMadeAt(?\DateTimeImmutable $groceryChoiceMadeAt): static
    {
        $this->groceryChoiceMadeAt = $groceryChoiceMadeAt;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        $doc = parent::toSearchDocument();
        $doc['date'] = $this->date?->format('Y-m-d');
        $doc['slot'] = $this->slot->value;
        $doc['recipeIds'] = $this->recipes->map(fn (Recipe $r) => (string) $r->getId())->getValues();
        $doc['groceryChoiceMadeAt'] = $this->groceryChoiceMadeAt?->format('c');

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
            ])->getValues(),
            'groceryChoiceMadeAt' => $this->groceryChoiceMadeAt?->format('c'),
        ], $changedProperties);
    }
}
