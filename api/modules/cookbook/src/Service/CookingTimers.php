<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Enum\CookingTimerAction;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Cookbook\Exception\CookingTimerNotFoundException;
use Maggie\Cookbook\Message\FinishCookingTimerCommand;
use Maggie\Cookbook\Repository\CookingTimerRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Core\Entity\User;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Message\MarkNotificationReadCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\DelayStamp;
use Symfony\Component\Messenger\Stamp\DispatchAfterCurrentBusStamp;
use Symfony\Component\Uid\Ulid;

/**
 * The timers of the Cuisine activity (MAG-582): what starts, changes and rings
 * them, whichever door it came through (REST, MCP, the worker).
 *
 * The end is a message delayed until `endsAt`. A change of schedule queues a
 * new one and leaves the old to find a different revision; the end that does
 * match turns the timer `done` and raises one notification, which the
 * notification module pushes to every device. « OK » or a cancel marks it read,
 * so it leaves the other devices too.
 */
class CookingTimers
{
    public const DEFAULT_ADD_SECONDS = 60;

    public function __construct(
        private readonly CookingTimerRepository $timers,
        private readonly RecipeRepository $recipes,
        private readonly NotificationRepository $notifications,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ClockInterface $clock,
    ) {
    }

    /** @throws \DomainException a refused value, or a recipe that is not the user's */
    public function start(User $user, string $label, string $recipeId, int $stepPosition, int $durationSeconds): CookingTimer
    {
        $id = self::idOf($recipeId);
        // Not an id at all: refused here, before Doctrine fails to convert it.
        $recipe = (Ulid::isValid($id) ? $this->recipes->findOneForUser($id, $user) : null)
            ?? throw new \DomainException(\sprintf('Recipe not found: %s', $recipeId));

        $timer = new CookingTimer($user, $label, $recipe, $stepPosition, $durationSeconds, $this->clock->now());
        $this->em->persist($timer);
        $this->em->flush();

        $this->scheduleEnd($timer);

        return $timer;
    }

    /** @throws \DomainException a refused change; {@see CookingTimerNotFoundException} when it is not the user's */
    public function change(User $user, string $timerId, CookingTimerAction $action, ?int $seconds = null): CookingTimer
    {
        $timer = $this->timers->findOneForUser($timerId, $user)
            ?? throw new CookingTimerNotFoundException($timerId);
        $now = $this->clock->now();
        $wasDone = CookingTimerStatus::Done === $timer->getStatus();

        match ($action) {
            CookingTimerAction::Pause => $timer->pause($now),
            CookingTimerAction::Resume => $timer->resume($now),
            CookingTimerAction::Add => $timer->add($seconds ?? self::DEFAULT_ADD_SECONDS, $now),
            CookingTimerAction::Cancel => $timer->cancel(),
            CookingTimerAction::Dismiss => $timer->dismiss(),
        };
        $this->em->flush();

        if (CookingTimerStatus::Running === $timer->getStatus()) {
            $this->scheduleEnd($timer);
        }
        if ($wasDone) {
            $this->markNotificationRead($timer);
        }

        return $timer;
    }

    /**
     * Rings the timer once its end is reached for the schedule the message was
     * queued for. Returns the timer when it rang, null otherwise.
     */
    public function finish(string $timerId, int $revision): ?CookingTimer
    {
        $timer = $this->timers->find($timerId);
        if (null === $timer) {
            return null;
        }

        $now = $this->clock->now();
        // Delivered a little early (a broker's delay is not to the second): queued again for what is left.
        if ($revision === $timer->getRevision() && CookingTimerStatus::Running === $timer->getStatus() && $timer->getEndsAt() > $now) {
            $this->scheduleEnd($timer);

            return null;
        }

        if (!$timer->finish($revision, $now)) {
            return null;
        }
        $this->em->flush();

        $this->bus->dispatch(new CreateNotificationCommand(
            type: NotificationType::CookingTimer->value,
            title: self::doneTitle($timer),
            body: self::doneBody($timer),
            relatedEntityIri: self::iriOf($timer),
            userId: (string) $timer->getUser()->getId(),
        ));

        return $timer;
    }

    /** « Pâtes : c'est prêt » — a label of any gender and number reads right. */
    public static function doneTitle(CookingTimer $timer): string
    {
        return \sprintf('%s : c\'est prêt', $timer->getLabel());
    }

    /** « minuteur de l'étape 3 · Pâtes aux légumes ». */
    public static function doneBody(CookingTimer $timer): string
    {
        $body = \sprintf('minuteur de l\'étape %d', $timer->getStepPosition());
        $recipe = $timer->getRecipe();

        return null === $recipe ? $body : $body.' · '.$recipe->getName();
    }

    public static function iriOf(CookingTimer $timer): string
    {
        return CookingTimer::COLLECTION.'/'.$timer->getId();
    }

    private function scheduleEnd(CookingTimer $timer): void
    {
        $endsAt = $timer->getEndsAt();
        if (null === $endsAt) {
            return;
        }

        $delayMs = max(0, ($endsAt->getTimestamp() - $this->clock->now()->getTimestamp()) * 1000);

        $this->bus->dispatch(
            new FinishCookingTimerCommand((string) $timer->getId(), $timer->getRevision()),
            [new DelayStamp($delayMs), new DispatchAfterCurrentBusStamp()],
        );
    }

    /** Seen on one device: the notification leaves the others. */
    private function markNotificationRead(CookingTimer $timer): void
    {
        foreach ($this->notifications->findUnreadByTypeAndEntity($timer->getUser(), NotificationType::CookingTimer, self::iriOf($timer)) as $notification) {
            $this->bus->dispatch(new MarkNotificationReadCommand((string) $notification->getId(), $this->clock->now()));
        }
    }

    /** An id or the recipe's IRI. */
    private static function idOf(string $recipe): string
    {
        $parts = explode('/', rtrim($recipe, '/'));

        return end($parts);
    }
}
