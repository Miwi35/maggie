<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Product;
use Maggie\Cookbook\Enum\ProductCategory;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\CreateProductCommand;
use Maggie\Cookbook\UseCase\CreateProduct;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateProductHandler
{
    public function __construct(
        private readonly CreateProduct $createProduct,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateProductCommand $command): Product
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $product = new Product();
        $product->setUser($user);
        $product->setName($command->name);
        $product->setCategory(ProductCategory::from($command->category));

        if ($command->defaultUnit !== null) {
            $product->setDefaultUnit(Unit::from($command->defaultUnit));
        }

        if ($command->ciqualAlimCode !== null) {
            $product->setCiqualAlimCode($command->ciqualAlimCode);
        }
        $product->setKcalPer100g($command->kcalPer100g);
        $product->setProteinPer100g($command->proteinPer100g);
        $product->setCarbsPer100g($command->carbsPer100g);
        $product->setFatPer100g($command->fatPer100g);

        return $this->createProduct->execute($product);
    }
}
