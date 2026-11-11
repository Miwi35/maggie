<?php

declare(strict_types=1);

namespace Maggie\Health\EventSubscriber;

use Maggie\Health\Exception\WorkoutScheduleException;
use Symfony\Component\EventDispatcher\EventSubscriberInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Event\ExceptionEvent;
use Symfony\Component\HttpKernel\KernelEvents;

/** A refused week answers 400 {error, field, current}, the shape manage_workouts gives (MAG-776). */
final class WorkoutScheduleRefusalResponse implements EventSubscriberInterface
{
    public static function getSubscribedEvents(): array
    {
        return [KernelEvents::EXCEPTION => ['onException', 64]];
    }

    public function onException(ExceptionEvent $event): void
    {
        $e = $event->getThrowable();
        if ($e instanceof WorkoutScheduleException) {
            $event->setResponse(new JsonResponse(['error' => $e->getMessage(), 'field' => $e->field, 'current' => $e->current], 400));
        }
    }
}
