<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\SnoozePurchaseSuggestionCommand;
use Maggie\Grocery\Service\PurchaseSuggestions;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Uid\Ulid;

/**
 * Puts the suggestion off for one usual interval: the product comes back at
 * the next cycle, not never (MAG-495).
 */
#[AsMessageHandler]
class SnoozePurchaseSuggestionHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PurchaseSuggestions $suggestions,
    ) {
    }

    /** @throws \DomainException when the product is unknown, another user's, or has no rhythm */
    public function __invoke(SnoozePurchaseSuggestionCommand $command): Product
    {
        $product = Ulid::isValid($command->productId) ? $this->em->find(Product::class, $command->productId) : null;

        if (null === $product || (string) $product->getUser()->getId() !== $command->userId) {
            throw new \DomainException(sprintf('Product not found: %s', $command->productId));
        }

        $rhythm = $this->suggestions->rhythmOf($product->getUser(), $product);
        if (null === $rhythm) {
            throw new \DomainException(sprintf('"%s" was not bought often enough to be suggested.', $product->getName()));
        }

        $product->setSuggestionSnoozedUntil($this->suggestions->today()->modify(sprintf('+%d days', $rhythm->everyDays)));
        $this->em->flush();

        return $product;
    }
}
