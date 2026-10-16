<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\DayReview;
use Maggie\Calendar\Message\CreateDayReviewCommand;
use Maggie\Calendar\Message\SetDayReviewCommand;
use Maggie\Calendar\Repository\DayReviewRepository;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The evening's review (MAG-500), by local date: created empty on first
 * read; the PUT is « Bonne nuit » — it replaces what helped, the whole list
 * among {@see DayReview::HELPED}, and marks the review done.
 */
final class DayReviewController
{
    public function __construct(
        private readonly Security $security,
        private readonly DayReviewRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/day-reviews/{date}', name: 'api_day_review_read', methods: ['GET'])]
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

        return new JsonResponse(self::describe($this->forDate($user, $day)));
    }

    #[Route('/api/day-reviews/{date}', name: 'api_day_review_update', methods: ['PUT'])]
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

        if (!\is_array($body) || !\is_array($body['helped'] ?? null) || !array_is_list($body['helped'])) {
            return new JsonResponse(['error' => 'Provide "helped": the list of what helped, among '.implode(', ', DayReview::HELPED).' (empty for none).'], Response::HTTP_BAD_REQUEST);
        }

        $review = $this->forDate($user, $day);

        try {
            $this->bus->dispatch(new SetDayReviewCommand(
                userId: (string) $user->getId(),
                dayReviewId: (string) $review->getId(),
                helped: $body['helped'],
            ));
        } catch (HandlerFailedException $e) {
            // A refused list is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(self::describe($review));
    }

    /** The user's review, laid down empty on first read through the bus, so the open screens hear of it. */
    private function forDate(User $user, \DateTimeImmutable $day): DayReview
    {
        $review = $this->repository->findOneByUserAndDate($user, $day);
        if (null !== $review) {
            return $review;
        }

        $created = $this->bus->dispatch(new CreateDayReviewCommand((string) $user->getId(), $day))
            ->last(HandledStamp::class)?->getResult();
        \assert($created instanceof DayReview);

        return $created;
    }

    /** @return array{id: string, date: string, helped: list<string>, reviewedAt: string|null} */
    private static function describe(DayReview $review): array
    {
        return [
            'id' => (string) $review->getId(),
            'date' => $review->getDate()->format('Y-m-d'),
            'helped' => $review->getHelped(),
            'reviewedAt' => $review->getReviewedAt()?->format(\DATE_ATOM),
        ];
    }
}
