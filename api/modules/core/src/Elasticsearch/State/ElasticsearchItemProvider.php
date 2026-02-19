<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Elastic\Elasticsearch\Client;
use Elastic\Elasticsearch\Exception\ClientResponseException;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Psr\Log\LoggerInterface;
use Symfony\Bundle\SecurityBundle\Security;
final class ElasticsearchItemProvider implements ProviderInterface
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexMetadataReader $metadataReader,
        private readonly ElasticsearchEntityHydrator $hydrator,
        private readonly Security $security,
        private readonly LoggerInterface $logger,
        private readonly ProviderInterface $doctrineProvider,
    ) {}

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        $entityClass = $operation->getClass();
        $meta = $this->metadataReader->read($entityClass);

        if ($meta === null) {
            return $this->doctrineProvider->provide($operation, $uriVariables, $context);
        }

        $id = $uriVariables['id'] ?? null;
        if ($id === null) {
            return $this->doctrineProvider->provide($operation, $uriVariables, $context);
        }

        try {
            return $this->doProvide($meta, $entityClass, (string) $id);
        } catch (\Throwable $e) {
            $this->logger->warning('ES item query failed, falling back to Doctrine: {error}', [
                'error' => $e->getMessage(),
                'entity' => $entityClass,
                'id' => (string) $id,
            ]);

            return $this->doctrineProvider->provide($operation, $uriVariables, $context);
        }
    }

    /**
     * @param array{index: string, module: ?string, fields: array<string, mixed>, relations: array<string, mixed>} $meta
     */
    private function doProvide(array $meta, string $entityClass, string $id): ?object
    {
        try {
            $response = $this->client->get([
                'index' => $meta['index'],
                'id' => $id,
            ])->asArray();
        } catch (ClientResponseException $e) {
            if ($e->getCode() === 404) {
                return null;
            }
            throw $e;
        }

        $source = $response['_source'];
        $source['id'] = $response['_id'];

        // Verify user ownership
        $userId = $this->getCurrentUserId();
        if (isset($source['userId']) && $source['userId'] !== $userId) {
            return null; // Not owned by this user → 404
        }

        return $this->hydrator->hydrate($source, $entityClass);
    }

    private function getCurrentUserId(): string
    {
        $user = $this->security->getUser();

        if ($user instanceof \Maggie\Core\Entity\User) {
            return (string) $user->getId();
        }

        throw new \RuntimeException('No authenticated user');
    }
}
