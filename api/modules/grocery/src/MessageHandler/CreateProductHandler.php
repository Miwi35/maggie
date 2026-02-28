<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Message\CreateProductCommand;
use Maggie\Grocery\UseCase\CreateProduct;
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

        return $this->createProduct->execute($product);
    }
}
