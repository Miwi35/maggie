<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Product;
use Maggie\Cookbook\Enum\ProductCategory;
use Maggie\Cookbook\Enum\Unit;
use Maggie\Cookbook\Message\UpdateProductCommand;
use Maggie\Cookbook\Repository\ProductRepository;
use Maggie\Cookbook\UseCase\UpdateProduct;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateProductHandler
{
    public function __construct(
        private readonly UpdateProduct $updateProduct,
        private readonly ProductRepository $productRepository,
    ) {
    }

    public function __invoke(UpdateProductCommand $command): Product
    {
        $product = $this->productRepository->find($command->productId)
            ?? throw new \DomainException("Product not found: {$command->productId}");

        if ($command->name !== null) {
            $product->setName($command->name);
        }
        if ($command->category !== null) {
            $product->setCategory(ProductCategory::from($command->category));
        }
        if ($command->defaultUnit !== null) {
            $product->setDefaultUnit(Unit::from($command->defaultUnit));
        }
        if ($command->ciqualAlimCode !== null) {
            $product->setCiqualAlimCode($command->ciqualAlimCode);
        }
        if ($command->kcalPer100g !== null) {
            $product->setKcalPer100g($command->kcalPer100g);
        }
        if ($command->proteinPer100g !== null) {
            $product->setProteinPer100g($command->proteinPer100g);
        }
        if ($command->carbsPer100g !== null) {
            $product->setCarbsPer100g($command->carbsPer100g);
        }
        if ($command->fatPer100g !== null) {
            $product->setFatPer100g($command->fatPer100g);
        }

        return $this->updateProduct->execute($product);
    }
}
