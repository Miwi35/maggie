<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Finance\Category\StandardCategories;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Repository\CategoryRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Lays down the categories a budget starts from.
 *
 * Safe to run twice: a heading the user already has — under whatever spelling
 * or casing — is left exactly as it is, colour and obligation included. Someone
 * who renamed "Loisirs" or made it optional must not find their choice undone
 * by asking for the starting set again.
 */
class InstallStandardCategories
{
    public function __construct(
        private readonly CategoryRepository $categoryRepository,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return array{created: int, kept: int, names: list<string>} */
    public function execute(User $user): array
    {
        $existing = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            $existing[self::key($category->getName())] = $category;
        }

        $created = [];
        $kept = 0;

        foreach (StandardCategories::all() as $definition) {
            $parent = $existing[self::key($definition['name'])] ?? null;

            if ($parent === null) {
                $parent = $this->create(
                    $user,
                    $definition['name'],
                    $definition['obligation'],
                    $definition['color'],
                    $definition['icon'],
                );
                $existing[self::key($definition['name'])] = $parent;
                $created[] = $parent;
            } else {
                ++$kept;
            }

            foreach ($definition['children'] ?? [] as $child) {
                if (isset($existing[self::key($child['name'])])) {
                    ++$kept;
                    continue;
                }

                $category = $this->create(
                    $user,
                    $child['name'],
                    $definition['obligation'],
                    $definition['color'],
                    $child['icon'],
                );
                $category->setParent($parent);
                $existing[self::key($child['name'])] = $category;
                $created[] = $category;
            }
        }

        $this->em->flush();

        // Written straight to the database, so nothing on the bus indexed them.
        foreach ($created as $category) {
            $this->bus->dispatch(new IndexDocumentCommand(
                entityClass: Category::class,
                entityId: (string) $category->getId(),
            ));
        }

        return [
            'created' => \count($created),
            'kept' => $kept,
            'names' => array_map(static fn (Category $category) => $category->getName(), $created),
        ];
    }

    private function create(
        User $user,
        string $name,
        ObligationFlag $obligation,
        string $color,
        string $icon,
    ): Category {
        $category = new Category();
        $category->setUser($user);
        $category->setName($name);
        $category->setObligation($obligation);
        $category->setColor($color);
        $category->setIcon($icon);

        $this->em->persist($category);

        return $category;
    }

    /** Names are compared the way a person reads them, not byte for byte. */
    private static function key(string $name): string
    {
        return mb_strtolower(trim($name));
    }
}
