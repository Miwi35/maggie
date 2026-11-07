<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Enum\FoodIntakeSource;
use Maggie\Health\Message\UpdateFoodIntakeCommand;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\Service\FoodIntakeRules;
use Maggie\Health\UseCase\SaveFoodIntake;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is written: a refused update leaves
 * the intake as it was. A meal's intake stays on the meal's day; any other
 * moves with its `eatenAt`.
 */
#[AsMessageHandler]
class UpdateFoodIntakeHandler
{
    public function __construct(
        private readonly SaveFoodIntake $saveFoodIntake,
        private readonly FoodIntakeRepository $intakes,
        private readonly FoodIntakeRules $rules,
    ) {
    }

    public function __invoke(UpdateFoodIntakeCommand $command): FoodIntake
    {
        $intake = $this->intakes->findOneOfUser($command->userId, $command->foodIntakeId)
            ?? throw new \DomainException("Food intake not found: {$command->foodIntakeId}");

        $eatenAt = null === $command->eatenAt ? $intake->getEatenAt() : $this->rules->eatenAt($command->eatenAt);
        $label = $command->label ?? $intake->getLabel();
        $quantity = $command->clearQuantity ? null : ($command->quantityGrams ?? $intake->getQuantityGrams());
        $kcal = $command->kcal ?? $intake->getKcal();
        $protein = $command->proteinG ?? $intake->getProteinG();
        $carbs = $command->carbsG ?? $intake->getCarbsG();
        $fat = $command->fatG ?? $intake->getFatG();
        $this->rules->assert($label, $kcal, $protein, $carbs, $fat, $quantity);
        \assert(null !== $eatenAt);

        $intake
            ->setEatenAt($eatenAt)
            ->setLabel(trim((string) $label))
            ->setQuantityGrams($quantity)
            ->setKcal($kcal)
            ->setProteinG($protein)
            ->setCarbsG($carbs)
            ->setFatG($fat);
        if (FoodIntakeSource::Meal !== $intake->getSource()) {
            $intake->setDate($this->rules->dayOf($eatenAt));
        }

        return $this->saveFoodIntake->execute($intake);
    }
}
