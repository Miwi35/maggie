<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Exception\InvalidHealthDayException;
use Maggie\Health\Message\UpdateHealthDayCommand;
use Maggie\Health\Service\HealthDayRules;
use Maggie\Health\UseCase\UpdateHealthDay;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Everything is checked before anything is written: a refused update leaves the day as it was. */
#[AsMessageHandler]
class UpdateHealthDayHandler
{
    private const FIELDS = ['mood', 'fatigue', 'note'];

    public function __construct(
        private readonly UpdateHealthDay $updateHealthDay,
        private readonly UserRepository $users,
        private readonly HealthDayRules $rules,
    ) {
    }

    public function __invoke(UpdateHealthDayCommand $command): HealthDay
    {
        $user = $this->users->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $date = $this->rules->date($command->date);

        $values = [];
        foreach ($command->cleared as $field) {
            if (!\in_array($field, self::FIELDS, true)) {
                throw new InvalidHealthDayException($field, 'is not a field of the journal: '.implode(', ', self::FIELDS).'.');
            }
            $values[$field] = null;
        }
        if (null !== $command->mood) {
            $values['mood'] = $this->rules->score($command->mood, 'mood');
        }
        if (null !== $command->fatigue) {
            $values['fatigue'] = $this->rules->score($command->fatigue, 'fatigue');
        }
        if (null !== $command->note) {
            $values['note'] = $this->rules->note($command->note);
        }
        if ([] === $values) {
            throw new InvalidHealthDayException(null, 'Give at least one of: '.implode(', ', self::FIELDS).'.');
        }

        return $this->updateHealthDay->execute($user, $date, $command->author, $values);
    }
}
