<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Message\GrantPalierCommand;
use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\RewardMessages;
use Maggie\Core\Entity\User;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * One palier per subject: a reward of level `palier` with no star, so the
 * constellation does not count it, and a message of the palier bank in the
 * user's tone, never the previous reward's. Published by the projection, as
 * every reward is.
 */
#[AsMessageHandler]
class GrantPalierHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly RewardRepository $rewards,
        private readonly TaskSettingsRepository $settings,
        private readonly RewardMessages $messages,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(GrantPalierCommand $command): ?Reward
    {
        $user = $this->em->find(User::class, $command->userId);
        if (!$user instanceof User) {
            return null;
        }

        $existing = $this->rewards->findOneBySubject($user, $command->subjectType, $command->subjectId);
        if (null !== $existing) {
            return $existing;
        }

        $tone = $this->settings->findOneByUser($user)?->getTone() ?? TaskSettings::DEFAULT_TONE;
        $drawn = $this->messages->drawPalier($tone, $this->rewards->findLatest($user)?->getMessageKey());

        $reward = (new Reward())
            ->setUser($user)
            ->setSubjectType($command->subjectType)
            ->setSubjectId($command->subjectId)
            ->setLevel(Reward::LEVEL_PALIER)
            ->setMessageKey($drawn['key'])
            ->setMessage($drawn['message'])
            ->setTitle(mb_substr($command->title, 0, 255))
            ->setStar(false)
            ->setEarnedAt($this->clock->now());
        $this->em->persist($reward);
        $this->em->flush();

        return $reward;
    }
}
