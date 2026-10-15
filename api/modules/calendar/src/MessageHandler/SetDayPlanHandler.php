<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Message\SetDayPlanCommand;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskPostponement;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is set: a refused list changes
 * nothing. Refusals are {@see \DomainException}s, the caller's to read.
 */
#[AsMessageHandler]
class SetDayPlanHandler
{
    public function __construct(
        private readonly DayPlanRepository $repository,
        private readonly TaskRepository $taskRepository,
        private readonly TaskPostponement $postponement,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(SetDayPlanCommand $command): DayPlan
    {
        $plan = $this->repository->findOneBy(['id' => $command->dayPlanId, 'user' => $command->userId])
            ?? throw new \DomainException("Day plan not found: {$command->dayPlanId}");

        if (\count($command->slots) > DayPlan::MAX_SLOTS) {
            throw new \DomainException(\sprintf('A day holds %d slots at most: put the others in the reserve, or in place of one of the %d.', DayPlan::MAX_SLOTS, DayPlan::MAX_SLOTS));
        }

        $slots = [];
        foreach ($command->slots as $index => $slot) {
            if (!\is_array($slot)) {
                throw new \DomainException(\sprintf('Slot %d must be an object {type, taskId}.', $index + 1));
            }
            $type = $slot['type'] ?? DayPlan::TYPE_TASK;
            if (DayPlan::TYPE_SAGA_STEP === $type) {
                throw new \DomainException('A saga step cannot take a slot yet: only "task" slots are available.');
            }
            if (DayPlan::TYPE_TASK !== $type) {
                throw new \DomainException(\sprintf('Slot %d: unknown type "%s". Known types: %s.', $index + 1, \is_scalar($type) ? (string) $type : get_debug_type($type), implode(', ', DayPlan::TYPES)));
            }
            $taskId = $slot['taskId'] ?? null;
            if (!\is_string($taskId) || '' === $taskId) {
                throw new \DomainException(\sprintf('Slot %d: taskId is required.', $index + 1));
            }
            $slots[] = ['type' => DayPlan::TYPE_TASK, 'taskId' => $taskId];
        }

        $ids = array_column($slots, 'taskId');
        if (\count($ids) !== \count(array_unique($ids))) {
            throw new \DomainException('A task takes one slot of the day, not two.');
        }

        $owned = $this->taskRepository->findOwnedByIds($plan->getUser(), $ids);
        foreach ($slots as $index => $slot) {
            $task = $owned[$slot['taskId']] ?? null;
            if (null === $task) {
                throw new \DomainException("Task not found: {$slot['taskId']}");
            }
            // Spelled as the API spells it, whatever case the caller sent.
            $slots[$index]['taskId'] = (string) $task->getId();
        }

        $plan->setSlots($slots);
        if ($command->confirm && null === $plan->getConfirmedAt()) {
            $plan->setConfirmedAt($this->clock->now());
        }

        $this->em->flush();
        $this->postponement->reset();

        return $plan;
    }
}
