<?php

declare(strict_types=1);

namespace Maggie\Proaction\Mcp\Tool;

use Maggie\Core\Repository\UserRepository;
use Maggie\Proaction\Entity\Proaction;
use Doctrine\ORM\EntityManagerInterface;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'schedule_proaction', description: 'Schedule an autonomous proaction for the agent to execute at a given time. The agent will be called with the prompt at the scheduled time. Date format: ISO 8601 (e.g. 2026-02-16T08:00:00+01:00).')]
class ScheduleProactionTool
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(string $scheduledAt, string $prompt): string
    {
        try {
            $dateTime = new \DateTimeImmutable($scheduledAt);
        } catch (\Exception $e) {
            return json_encode(['error' => 'Invalid date format: ' . $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        // Use first user as default (single-user system)
        $users = $this->userRepository->findAll();
        if (count($users) === 0) {
            return json_encode(['error' => 'No user found'], JSON_THROW_ON_ERROR);
        }
        $user = $users[0];

        $proaction = new Proaction();
        $proaction->setUser($user);
        $proaction->setScheduledAt($dateTime);
        $proaction->setPrompt($prompt);

        $this->em->persist($proaction);
        $this->em->flush();

        return json_encode([
            'success' => true,
            'proaction' => [
                'id' => (string) $proaction->getId(),
                'scheduledAt' => $proaction->getScheduledAt()->format('c'),
                'prompt' => $proaction->getPrompt(),
                'status' => $proaction->getStatus()->value,
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
