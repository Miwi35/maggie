<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Message\GrantTaskRewardCommand;
use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\RewardMessages;
use Maggie\Calendar\Service\RewardSurprises;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * One reward per task, a star: a surprise when a rule of {@see RewardSurprises}
 * holds, else a message of the tone set (« doux » before any setting), never
 * the one of the previous reward.
 */
#[AsMessageHandler]
class GrantTaskRewardHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly RewardRepository $rewards,
        private readonly TaskSettingsRepository $settings,
        private readonly RewardSurprises $surprises,
        private readonly RewardMessages $messages,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(GrantTaskRewardCommand $command): ?Reward
    {
        $task = $this->em->getRepository(Task::class)->findOneBy(['id' => $command->taskId, 'user' => $command->userId]);
        if (!$task instanceof Task) {
            return null;
        }
        $user = $task->getUser();

        $existing = $this->rewards->findOneBySubject($user, Reward::SUBJECT_TASK, $command->taskId);
        if (null !== $existing) {
            return $existing;
        }

        // Earned now, when the user sees it: a sync bringing in an old completion does not rewrite a past day.
        $earnedAt = $this->clock->now();
        $surprise = $this->surprises->for($task, $earnedAt);
        $tone = $this->settings->findOneByUser($user)?->getTone() ?? TaskSettings::DEFAULT_TONE;
        $drawn = $this->messages->draw($tone, $surprise['surprise'] ?? null, $this->rewards->findLatest($user)?->getMessageKey(), $surprise['values'] ?? []);

        $reward = (new Reward())
            ->setUser($user)
            ->setSubjectType(Reward::SUBJECT_TASK)
            ->setSubjectId($command->taskId)
            ->setLevel(null === $surprise ? Reward::LEVEL_STANDARD : Reward::LEVEL_SURPRISE)
            ->setSurprise($surprise['surprise'] ?? null)
            ->setMessageKey($drawn['key'])
            ->setMessage($drawn['message'])
            ->setStar(true)
            ->setEarnedAt($earnedAt);
        $this->em->persist($reward);
        $this->em->flush();

        return $reward;
    }
}
