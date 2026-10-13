<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Contract\IndexableInterface;

final class IndexableEntityRegistry
{
    /** @var array<string, class-string<IndexableInterface>>|null */
    private ?array $registry = null;

    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly IndexMetadataReader $metadataReader,
    ) {
    }

    /**
     * @return array<string, class-string<IndexableInterface>> Map of index name → entity FQCN
     */
    public function getAll(): array
    {
        if (null === $this->registry) {
            $this->registry = $this->discover();
        }

        return $this->registry;
    }

    /**
     * The indices a global search queries when the caller names none: those that
     * did not opt out with `#[Indexed(globalSearch: false)]`.
     *
     * @return list<string>
     */
    public function getGlobalSearchIndices(): array
    {
        $indices = [];
        foreach ($this->getAll() as $index => $class) {
            if ($this->metadataReader->read($class)['globalSearch'] ?? true) {
                $indices[] = $index;
            }
        }

        return $indices;
    }

    /**
     * @return array<string, class-string<IndexableInterface>>
     */
    public function getByModule(string $module): array
    {
        return array_filter(
            $this->getAll(),
            fn (string $class) => $this->metadataReader->read($class)['module'] === $module,
        );
    }

    /**
     * @return array<string, class-string<IndexableInterface>>
     */
    private function discover(): array
    {
        $result = [];
        $allMetadata = $this->em->getMetadataFactory()->getAllMetadata();

        foreach ($allMetadata as $meta) {
            $class = $meta->getName();

            if (!is_subclass_of($class, IndexableInterface::class)) {
                continue;
            }

            $indexMeta = $this->metadataReader->read($class);
            if (null === $indexMeta) {
                continue;
            }

            $result[$indexMeta['index']] = $class;
        }

        return $result;
    }
}
