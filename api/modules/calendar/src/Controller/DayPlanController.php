<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Message\SetDayPlanCommand;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\DayProposal;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The day (MAG-497), by local date: created empty on first read; the PUT
 * replaces the whole list of slots — five at most. A future day can already
 * be written. `confirm: false` writes a proposal without settling the day.
 *
 * `/proposal` (MAG-498) reads the morning's five without writing anything:
 * « C'est parti » is the PUT of the day with those five.
 */
final class DayPlanController
{
    public function __construct(
        private readonly Security $security,
        private readonly DayPlans $dayPlans,
        private readonly DayProposal $proposal,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/day-plans/{date}', name: 'api_day_plan_read', methods: ['GET'])]
    public function read(string $date): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $day = DayPlans::day($date);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->dayPlans->describe($this->dayPlans->forDate($user, $day)));
    }

    #[Route('/api/day-plans/{date}/proposal', name: 'api_day_plan_proposal', methods: ['GET'])]
    public function propose(string $date): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $day = DayPlans::day($date);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->proposal->propose($user, $day));
    }

    #[Route('/api/day-plans/{date}', name: 'api_day_plan_update', methods: ['PUT'])]
    public function update(Request $request, string $date): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $day = DayPlans::day($date);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }

        if (!\is_array($body) || !\is_array($body['slots'] ?? null) || !array_is_list($body['slots'])) {
            return new JsonResponse(['error' => 'Provide "slots": the list of the day\'s slots, each {type: "task", taskId}, in order.'], Response::HTTP_BAD_REQUEST);
        }
        if (\array_key_exists('confirm', $body) && !\is_bool($body['confirm'])) {
            return new JsonResponse(['error' => '"confirm" must be true or false.'], Response::HTTP_BAD_REQUEST);
        }

        $plan = $this->dayPlans->forDate($user, $day);

        try {
            $this->bus->dispatch(new SetDayPlanCommand(
                userId: (string) $user->getId(),
                dayPlanId: (string) $plan->getId(),
                slots: $body['slots'],
                confirm: $body['confirm'] ?? true,
            ));
        } catch (HandlerFailedException $e) {
            // A refused list is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->dayPlans->describe($plan));
    }
}
