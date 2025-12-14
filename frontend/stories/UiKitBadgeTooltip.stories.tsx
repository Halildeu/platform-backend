import React from 'react';
import type { Meta, StoryObj } from '@storybook/react';
import { Badge, Tooltip } from 'mfe-ui-kit';

const meta: Meta = {
  title: 'UI Kit/Primitives/BadgeTooltip',
};

export default meta;

type Story = StoryObj;

export const BadgeTones: Story = {
  render: () => (
    <div className="flex flex-wrap gap-3 p-4">
      <Badge tone="default">Default</Badge>
      <Badge tone="info">Info</Badge>
      <Badge tone="success">Success</Badge>
      <Badge tone="warning">Warning</Badge>
      <Badge tone="danger">Danger</Badge>
      <Badge tone="muted">Muted</Badge>
    </div>
  ),
};

export const BadgeWithTooltip: Story = {
  render: () => (
    <div className="flex gap-3 p-4">
      <Tooltip text="Aktif kullanıcı">
        <Badge tone="success">ACTIVE</Badge>
      </Tooltip>
      <Tooltip text="Askıya alınmış kullanıcı">
        <Badge tone="danger">SUSPENDED</Badge>
      </Tooltip>
    </div>
  ),
};

