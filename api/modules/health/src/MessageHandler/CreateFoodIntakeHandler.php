<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Enum\FoodIntakeSource;
use Maggie\Health\Exception\DuplicateFoodIntakeException;
use Maggie\Health\Exception\InvalidFoodIntakeException;
use Maggie\Health\Message\CreateFoodIntakeCommand;
use Maggie\Health\Port\CiqualFood;
use Maggie\Health\Port\FoodCatalogInterface;
use Maggie\Health\Port\MealNutritionInterface;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\Service\FoodIntakeRules;
use Maggie\Health\UseCase\SaveFoodIntake;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * « Mangé » copies the meal's figures, one serving of each recipe, onto the
 * meal's day; a food typed or told counts for the day of `eatenAt`. A Ciqual
 * food with its grams gives the figures the request leaves out.
 */
#[AsMessageHandler]
class CreateFoodIntakeHandler
{
    public function __construct(
        private readonly SaveFoodIntake $saveFoodIntake,
        private readonly UserRepository $userRepository,
        private readonly FoodIntakeRepository $intakes,
        private readonly FoodIntakeRules $rules,
        private readonly MealNutritionInterface $meals,
        private readonly FoodCatalogInterface $foods,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(CreateFoodIntakeCommand $command): FoodIntake
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $intake = null === $command->mealId ? $this->food($command) : $this->meal($user, $command->mealId, $command->eatenAt);
        $intake->setUser($user);

        return $this->saveFoodIntake->execute($intake);
    }

    private function meal(User $user, string $mealId, ?string $eatenAt): FoodIntake
    {
        $portion = $this->meals->ofMeal($user, $mealId)
            ?? throw new InvalidFoodIntakeException('mealId', "meal not found: {$mealId}.");
        if (null !== $this->intakes->findOneByMeal($user, $mealId)) {
            throw new DuplicateFoodIntakeException();
        }
        if (null === $portion->kcal) {
            throw new InvalidFoodIntakeException('mealId', 'this meal has no figures: none of its recipes\' ingredients has kcal. Record what was eaten as a food instead.');
        }

        return (new FoodIntake())
            ->setMealId($portion->mealId)
            ->setDate(new \DateTimeImmutable($portion->date, new \DateTimeZone('UTC')))
            ->setEatenAt(null === $eatenAt ? $this->clock->now() : $this->rules->eatenAt($eatenAt))
            ->setLabel($portion->label)
            ->setKcal($portion->kcal)
            ->setProteinG($portion->proteinG)
            ->setCarbsG($portion->carbsG)
            ->setFatG($portion->fatG)
            ->setSource(FoodIntakeSource::Meal);
    }

    private function food(CreateFoodIntakeCommand $command): FoodIntake
    {
        $source = FoodIntakeSource::tryFrom($command->source);
        if (null === $source || FoodIntakeSource::Meal === $source) {
            throw new InvalidFoodIntakeException('source', "unknown value \"{$command->source}\". Use manual or conversation; a meal is recorded with mealId.");
        }
        if (null === $command->eatenAt) {
            throw new InvalidFoodIntakeException('eatenAt', 'is required without mealId: when was it eaten, e.g. 2026-11-07T12:30:00+01:00.');
        }
        $eatenAt = $this->rules->eatenAt($command->eatenAt);

        $label = $command->label;
        $kcal = $command->kcal;
        $protein = $command->proteinG;
        $carbs = $command->carbsG;
        $fat = $command->fatG;

        if (null !== $command->ciqualCode) {
            $this->rules->ciqualCode($command->ciqualCode);
            $food = $this->catalogFood($command->ciqualCode);
            $label ??= $food->name;
            if (null !== $command->quantityGrams) {
                $hundreds = $command->quantityGrams / 100;
                $kcal ??= null === $food->kcalPer100g ? null : (int) round($hundreds * $food->kcalPer100g);
                $protein ??= null === $food->proteinPer100g ? null : $hundreds * $food->proteinPer100g;
                $carbs ??= null === $food->carbsPer100g ? null : $hundreds * $food->carbsPer100g;
                $fat ??= null === $food->fatPer100g ? null : $hundreds * $food->fatPer100g;
            } elseif (null === $kcal) {
                throw new InvalidFoodIntakeException('quantityGrams', 'is required with ciqualCode unless kcal is given: how many grams were eaten?');
            }
        }

        $this->rules->assert($label, $kcal, $protein, $carbs, $fat, $command->quantityGrams, $command->ciqualCode);

        return (new FoodIntake())
            ->setDate($this->rules->dayOf($eatenAt))
            ->setEatenAt($eatenAt)
            ->setCiqualCode($command->ciqualCode)
            ->setLabel(trim((string) $label))
            ->setQuantityGrams($command->quantityGrams)
            ->setKcal($kcal)
            ->setProteinG($protein ?? 0.0)
            ->setCarbsG($carbs ?? 0.0)
            ->setFatG($fat ?? 0.0)
            ->setSource($source);
    }

    private function catalogFood(string $code): CiqualFood
    {
        try {
            $food = $this->foods->food($code);
        } catch (\RuntimeException) {
            throw new InvalidFoodIntakeException('ciqualCode', 'Ciqual cannot be reached: give kcal (and the macros) instead.');
        }

        return $food ?? throw new InvalidFoodIntakeException('ciqualCode', "unknown Ciqual food: {$code}.");
    }
}
