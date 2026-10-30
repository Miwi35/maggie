<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Message\UpdateHealthProfileCommand;
use Maggie\Health\Repository\HealthProfileRepository;
use Maggie\Health\Service\HealthProfileRules;
use Maggie\Health\UseCase\UpdateHealthProfile;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is written: a refused update leaves
 * the profile, and the weighings, as they were. The profile is laid down on
 * its first write.
 */
#[AsMessageHandler]
class UpdateHealthProfileHandler
{
    public function __construct(
        private readonly UpdateHealthProfile $updateHealthProfile,
        private readonly UserRepository $userRepository,
        private readonly HealthProfileRepository $profileRepository,
        private readonly HealthProfileRules $rules,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(UpdateHealthProfileCommand $command): HealthProfile
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $profile = $this->profileRepository->findOneByUser($user) ?? (new HealthProfile())->setUser($user);
        $currentYear = (int) $this->clock->now()->format('Y');

        $sex = null === $command->sex ? $profile->getSex() : $this->rules->sex($command->sex);
        $birthYear = null === $command->birthYear ? $profile->getBirthYear() : $this->rules->birthYear($command->birthYear, $currentYear);
        $heightCm = null === $command->heightCm ? $profile->getHeightCm() : $this->rules->heightCm($command->heightCm);
        $weightKg = null === $command->weightKg ? null : $this->rules->weightKg($command->weightKg);
        $activityLevel = null === $command->activityLevel ? $profile->getActivityLevel() : $this->rules->activityLevel($command->activityLevel);
        $mode = null === $command->mode ? $profile->getMode() : $this->rules->mode($command->mode);
        $enteredNeedKcal = null === $command->enteredNeedKcal ? $profile->getEnteredNeedKcal() : $this->rules->enteredNeedKcal($command->enteredNeedKcal);
        $stepGoal = null === $command->stepGoal ? $profile->getStepGoal() : $this->rules->stepGoal($command->stepGoal);
        $windowStart = null === $command->suggestionWindowStart ? $profile->getSuggestionWindowStart() : $this->rules->time($command->suggestionWindowStart, 'suggestionWindowStart');
        $windowEnd = null === $command->suggestionWindowEnd ? $profile->getSuggestionWindowEnd() : $this->rules->time($command->suggestionWindowEnd, 'suggestionWindowEnd');
        $this->rules->window($windowStart, $windowEnd);
        $sessionMinutes = null === $command->sessionMinutes ? $profile->getSessionMinutes() : $this->rules->sessionMinutes($command->sessionMinutes);
        $stepSource = null === $command->stepSource ? $profile->getStepSource() : $this->rules->stepSource($command->stepSource);

        $profile
            ->setSex($sex)
            ->setBirthYear($birthYear)
            ->setHeightCm($heightCm)
            ->setActivityLevel($activityLevel)
            ->setMode($mode)
            ->setEnteredNeedKcal($enteredNeedKcal)
            ->setStepGoal($stepGoal)
            ->setSuggestionWindowStart($windowStart)
            ->setSuggestionWindowEnd($windowEnd)
            ->setSessionMinutes($sessionMinutes)
            ->setStepSource($stepSource);

        return $this->updateHealthProfile->execute($profile, $weightKg);
    }
}
