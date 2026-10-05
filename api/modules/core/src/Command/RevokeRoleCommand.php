<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Maggie\Core\Entity\User;
use Symfony\Component\Console\Attribute\AsCommand;

#[AsCommand(name: 'app:user:revoke', description: 'Revoke a permission (role) from a user')]
final class RevokeRoleCommand extends AbstractUserRoleCommand
{
    protected function apply(User $user, string $role): void
    {
        $user->removeRole($role);
    }

    protected function successMessage(string $email, string $role): string
    {
        return sprintf('%s no longer has %s.', $email, $role);
    }
}
