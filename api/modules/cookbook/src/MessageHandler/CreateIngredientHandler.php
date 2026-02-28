<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Grocery\UseCase\CreateProduct;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateIngredientHandler
{
    public function __construct(
        private readonly CreateProduct $createProduct,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateIngredientCommand $command): Ingredient
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $ingredient = new Ingredient();
        $ingredient->setUser($user);
        $ingredient->setName($command->name);
        $ingredient->setCategory(ProductCategory::from($command->category));

        if ($command->defaultUnit !== null) {
            $ingredient->setDefaultUnit(Unit::from($command->defaultUnit));
        }

        if ($command->ciqualAlimCode !== null) {
            $ingredient->setCiqualAlimCode($command->ciqualAlimCode);
        }
        $ingredient->setKcalPer100g($command->kcalPer100g);
        $ingredient->setProteinPer100g($command->proteinPer100g);
        $ingredient->setCarbsPer100g($command->carbsPer100g);
        $ingredient->setFatPer100g($command->fatPer100g);

        return $this->createProduct->execute($ingredient);
    }
}
