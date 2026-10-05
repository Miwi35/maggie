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

        $wholeDay = new \DateTimeImmutable($day.' 00:00:00', $this->zone());
        parent::setStartAt($wholeDay);
        parent::setEndAt($wholeDay->setTime(23, 59, 59));
        $this->setAllDay(true);

        return $this;
    }

    public function setStartAt(\DateTimeImmutable $startAt): static
    {
        // An instant handed to a meal names a day, and moves it to that day.
        //
        // `Meal` shares its primary key with `event`, so an `Event` write
        // reaches a meal: `EventRepository::find()` returns one for a meal's
        // id, and `findByDateRange()` hands Maggie a meal among the events, id
        // and all. The `update_event` tool and `PATCH /api/events/{id}` then
        // call this. Left inherited, they would move the instants and leave
        // `date` — the field the API, Elasticsearch, Mercure and every week
        // view read — behind, so Maggie would answer "c'est décalé" and the
        // meal would not move anywhere the owner can see.
        //
        // The day is read in the meal's own time zone, as the migration reads
        // the rows the old clients wrote: an instant at 23:00 UTC is the next
        // day in Paris, and that is the day the writer meant.
        //
        // Comments, not a docblock: API Platform publishes a setter's docblock
        // as the description of the property it writes.
        return $this->setDate($startAt->setTimezone($this->zone()));
    }

    public function setEndAt(\DateTimeImmutable $endAt): static
    {
        // A meal ends when its day does, so there is no end to set — only the
        // day's bounds to re-derive.
        //
        // Deliberately not a move: `UpdateEventHandler` sets `startAt` and then
        // `endAt`, and a span whose two ends fall on different days would
        // otherwise leave the meal on the last one. `setStartAt()` above is the
        // one that names the day.
        if (null === $this->date) {
            // No day to derive from yet. Taking the instant keeps the typed
            // property initialised — `getEndAt()` on an `Event` whose end was
            // never set is an `Error`, not a null — and `setDate()` overwrites
            // it as soon as the day arrives.
            return parent::setEndAt($endAt);
        }

        return $this->setDate($this->date);
    }

    public function setAllDay(bool $allDay): static
    {
        // A meal covers its day, always. `UpdateEventHandler` sets `allDay`
        // *after* the instants, so without this `update_event allDay=false` on
        // a meal would leave the flag off next to 00:00–23:59:59 — the last
        // derived field the `Event` door could still knock out of step.
        //
        // Comments, not a docblock: API Platform publishes a setter's docblock
        // as the description of the property it writes.
        return parent::setAllDay(true);
    }

    /**
     * The meal's time zone, falling back on the column's default.
     *
     * `Event::$timeZone` is a free string with no constraint behind it, and the
     * `Event` write path above can set it: an unknown name would otherwise
     * throw out of a setter during denormalization and answer 500, where the
     * worst case here is a meal placed in the default zone's day.
     *
     * `Event` now refuses an unknown name (`Assert\Timezone`) and
     * `RecurrenceService` guards its reads; this fallback stays as a second
     * net for a row written before that (MAG-256).
     */
    private function zone(): \DateTimeZone
    {
        try {
            return new \DateTimeZone($this->getTimeZone());
        } catch (\Exception) {
            return new \DateTimeZone(self::DEFAULT_TIME_ZONE);
        }
    }

    public function setTimeZone(string $timeZone): static
    {
        // Re-derives the instants: moving the time zone moves the day's
        // bounds, and never the day itself. A comment, not a docblock — API
        // Platform would publish it as the description of the inherited
        // `timeZone` property.
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
