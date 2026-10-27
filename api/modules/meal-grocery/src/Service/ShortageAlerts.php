<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\AddGroceryItemCommand;
use Maggie\MealGrocery\Entity\ShortageAlert;
use Maggie\MealGrocery\Repository\ShortageAlertRepository;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Message\DeleteNotificationCommand;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

/**
 * « Il manque » (MAG-570): an ingredient of a meal planned today or tomorrow
 * that is neither in stock nor on the grocery list — ticked or not — raises one
 * interruption, once per product and per meal, never at night. The
 * notification stays in the list until the product is bought or added, then
 * goes by itself.
 *
 * The missing set is the one of the Accueil card ({@see RecipeNeeds::runningOutPerMeal()},
 * MAG-563), minus what the list already holds. Called every minute by
 * `maggie:notification:check-reminders`.
 */
final class ShortageAlerts
{
    /** Meals of today and tomorrow. */
    public const DAYS = 2;

    /** Today's lunch is over from then on: nobody shops for it anymore. */
    private const LUNCH_ENDS = '14:00';

    public function __construct(
        private readonly RecipeNeeds $recipeNeeds,
        private readonly ShortageAlertRepository $alerts,
        private readonly TaskSettingsRepository $settings,
        private readonly TaskOverdue $overdue,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * The ingredients still missing for the meals to come: not in stock, not on
     * the list, and for a meal not yet over.
     *
     * @return list<array{productId: string, name: string, date: string, slot: string, mealId: string, mealName: string}>
     */
    public function missing(User $user): array
    {
        $local = $this->local($user);
        $today = $this->overdue->today($user);
        $listed = $this->listedProductIds($user);

        return array_values(array_filter(
            $this->recipeNeeds->runningOutPerMeal($user, $today, self::DAYS),
            fn (array $entry): bool => !isset($listed[$entry['productId']]) && !$this->isOver($entry, $today, $local),
        ));
    }

    /** Settles what got covered, then raises what is missing. Returns the notifications sent. */
    public function run(User $user): int
    {
        $missing = $this->missing($user);

        $this->resolveCovered($user, $missing);

        $settings = $this->settings->findOneByUser($user) ?? (new TaskSettings())->setUser($user);
        if ($settings->isQuiet($this->local($user)->format('H:i'))) {
            return 0;
        }

        $sent = 0;
        foreach ($missing as $entry) {
            $meal = $this->em->find(Meal::class, $entry['mealId']);
            $product = $this->em->find(Product::class, $entry['productId']);
            if (!$meal instanceof Meal || !$product instanceof Product || null !== $this->alerts->findOneByMealAndProduct($meal, $product)) {
                continue;
            }

            $alert = (new ShortageAlert())->setUser($user)->setMeal($meal)->setProduct($product);
            $this->em->persist($alert);
            $this->em->flush();

            $this->bus->dispatch(new CreateNotificationCommand(
                type: NotificationType::Grocery->value,
                title: 'Il manque : '.$entry['name'],
                body: \sprintf('Pour %s, %s.', $entry['mealName'], $this->when($entry, $this->overdue->today($user))),
                relatedEntityIri: self::iri($alert),
                userId: (string) $user->getId(),
                actions: ShortageAlert::ACTIONS,
            ));
            // No notification when the user turned them off: the alert is kept all the same, so it never comes back later.
            ++$sent;
        }

        return $sent;
    }

    /**
     * « Ajouter à la liste », pressed on the notification: the product goes on
     * the list and the alert is settled.
     *
     * @return array{alert: array<string, mixed>, notification: null}
     *
     * @throws \InvalidArgumentException on an unknown action
     * @throws \DomainException          when the alert was already settled
     */
    public function act(ShortageAlert $alert, string $action): array
    {
        if (!\in_array($action, ShortageAlert::ACTIONS, true)) {
            throw new \InvalidArgumentException(\sprintf('action must be one of %s, not "%s".', implode(', ', ShortageAlert::ACTIONS), $action));
        }
        if ($alert->isResolved()) {
            throw new \DomainException('This alert was already settled: the product is bought or on the list.');
        }

        $product = $alert->getProduct();
        if (!isset($this->listedProductIds($alert->getUser())[(string) $product->getId()])) {
            try {
                $this->bus->dispatch(new AddGroceryItemCommand(
                    userId: (string) $alert->getUser()->getId(),
                    label: $product->getName(),
                    source: 'recipe',
                ));
            } catch (HandlerFailedException $e) {
                $cause = $e->getPrevious() ?? $e;
                throw $cause instanceof \DomainException ? $cause : $e;
            }
        }

        $this->resolve($alert);

        return ['alert' => $alert->describe(), 'notification' => null];
    }

    /**
     * An open alert that is no longer missing — bought, on the list, the meal
     * moved, changed or over — is settled, and its notification goes.
     *
     * @param list<array{productId: string, name: string, date: string, slot: string, mealId: string, mealName: string}> $missing
     */
    private function resolveCovered(User $user, array $missing): void
    {
        $still = [];
        foreach ($missing as $entry) {
            $still[$entry['mealId'].':'.$entry['productId']] = true;
        }

        foreach ($this->alerts->findUnresolved($user) as $alert) {
            if (!isset($still[$alert->getMeal()->getId().':'.$alert->getProduct()->getId()])) {
                $this->resolve($alert);
            }
        }
    }

    private function resolve(ShortageAlert $alert): void
    {
        $notification = $this->notificationOf($alert);
        $alert->setResolvedAt($this->clock->now());
        $this->em->flush();

        // Deleted, not marked read: a settled shortage has nothing left to say.
        if (null !== $notification) {
            $this->bus->dispatch(new DeleteNotificationCommand(notificationId: (string) $notification->getId()));
        }
    }

    private function notificationOf(ShortageAlert $alert): ?Notification
    {
        return $this->em->getRepository(Notification::class)->findOneBy([
            'user' => $alert->getUser(),
            'relatedEntityIri' => self::iri($alert),
        ]);
    }

    public static function iri(ShortageAlert $alert): string
    {
        return '/api/shortage-alerts/'.$alert->getId();
    }

    /** @return array<string, true> the products with a line on the list, ticked or not */
    private function listedProductIds(User $user): array
    {
        /** @var list<array{id: Ulid}> $rows */
        $rows = $this->em->getRepository(GroceryItem::class)->createQueryBuilder('i')
            ->select('p.id AS id')
            ->join('i.groceryList', 'l')
            ->join('i.product', 'p')
            ->where('l.user = :user')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getScalarResult();

        $ids = [];
        foreach ($rows as $row) {
            // A scalar result is the raw column (a UUID string), not the Ulid the entities hand out.
            $ids[Ulid::fromString((string) $row['id'])->toBase32()] = true;
        }

        return $ids;
    }

    /** @param array{date: string, slot: string} $entry */
    private function isOver(array $entry, \DateTimeImmutable $today, \DateTimeImmutable $local): bool
    {
        return $entry['date'] === $today->format('Y-m-d')
            && MealSlot::Lunch->value === $entry['slot']
            && $local->format('H:i') >= self::LUNCH_ENDS;
    }

    /**
     * « ce soir », « demain midi »…
     *
     * @param array{date: string, slot: string} $entry
     */
    private function when(array $entry, \DateTimeImmutable $today): string
    {
        $isToday = $entry['date'] === $today->format('Y-m-d');
        $lunch = MealSlot::Lunch->value === $entry['slot'];

        return match (true) {
            $isToday && $lunch => 'ce midi',
            $isToday => 'ce soir',
            $lunch => 'demain midi',
            default => 'demain soir',
        };
    }

    private function local(User $user): \DateTimeImmutable
    {
        return $this->clock->now()->setTimezone($this->overdue->zone($user));
    }
}
