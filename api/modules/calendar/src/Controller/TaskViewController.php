<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\TodayTasks;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The task views (MAG-405): a read of Doctrine, not of the index — what a view
 * holds depends on the day. `today` for now; the other views come with MAG-408.
 */
final class TaskViewController
{
    private const VIEWS = ['today'];

    public function __construct(
        private readonly TodayTasks $todayTasks,
        private readonly NormalizerInterface $normalizer,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/tasks/view/{view}', name: 'task_view', methods: ['GET'])]
    public function __invoke(string $view): JsonResponse
    {
        if (!\in_array($view, self::VIEWS, true)) {
            return new JsonResponse(
                ['error' => \sprintf('Unknown view "%s". Known views: %s.', $view, implode(', ', self::VIEWS))],
                Response::HTTP_BAD_REQUEST,
            );
        }

        /** @var User $user */
        $user = $this->security->getUser();

        $members = [];
        foreach ($this->todayTasks->for($user) as $task) {
            $members[] = $this->normalizer->normalize($task, 'jsonld', ['resource_class' => Task::class]);
        }

        return new JsonResponse([
            '@context' => '/api/contexts/Task',
            '@id' => '/api/tasks/view/'.$view,
            '@type' => 'Collection',
            'totalItems' => \count($members),
            'member' => $members,
        ], Response::HTTP_OK, ['Content-Type' => 'application/ld+json']);
    }
}
