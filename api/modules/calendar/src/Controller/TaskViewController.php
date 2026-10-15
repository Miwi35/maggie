<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\TaskViews;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The task views (MAG-405, MAG-408): a read of Doctrine, not of the index —
 * what a view holds depends on the day. Besides its tasks, every answer
 * carries the size of the four views, for the tabs. Only `done` is paged
 * (`page`, `itemsPerPage`).
 */
final class TaskViewController
{
    private const DEFAULT_ITEMS_PER_PAGE = 30;
    private const MAX_ITEMS_PER_PAGE = 100;

    public function __construct(
        private readonly TaskViews $views,
        private readonly NormalizerInterface $normalizer,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/tasks/view/{view}', name: 'task_view', methods: ['GET'])]
    public function __invoke(Request $request, string $view): JsonResponse
    {
        if (!\in_array($view, TaskViews::ALL, true)) {
            return $this->error(\sprintf('Unknown view "%s". Known views: %s.', $view, implode(', ', TaskViews::ALL)));
        }

        $page = $this->positiveInt($request, 'page', 1, null);
        $itemsPerPage = $this->positiveInt($request, 'itemsPerPage', self::DEFAULT_ITEMS_PER_PAGE, self::MAX_ITEMS_PER_PAGE);
        if (null === $page || null === $itemsPerPage) {
            return $this->error(\sprintf('"page" and "itemsPerPage" must be positive integers, itemsPerPage at most %d.', self::MAX_ITEMS_PER_PAGE));
        }

        /** @var User $user */
        $user = $this->security->getUser();

        $read = $this->views->read($user, $view, $page, $itemsPerPage);

        $members = [];
        foreach ($read['tasks'] as $task) {
            $members[] = $this->normalizer->normalize($task, 'jsonld', ['resource_class' => Task::class]);
        }

        $body = [
            '@context' => '/api/contexts/Task',
            '@id' => '/api/tasks/view/'.$view,
            '@type' => 'Collection',
            'totalItems' => $read['total'],
            'counts' => $read['counts'],
            'member' => $members,
        ];
        if (TaskViews::DONE === $view) {
            $body['page'] = $page;
            $body['itemsPerPage'] = $itemsPerPage;
        }

        return new JsonResponse($body, Response::HTTP_OK, ['Content-Type' => 'application/ld+json']);
    }

    private function positiveInt(Request $request, string $name, int $default, ?int $max): ?int
    {
        if (!$request->query->has($name)) {
            return $default;
        }

        $raw = $request->query->get($name);
        if (!\is_string($raw) || 1 !== preg_match('/^[1-9][0-9]{0,8}$/', $raw)) {
            return null;
        }

        $value = (int) $raw;

        return null !== $max && $value > $max ? null : $value;
    }

    private function error(string $message): JsonResponse
    {
        return new JsonResponse(['error' => $message], Response::HTTP_BAD_REQUEST);
    }
}
