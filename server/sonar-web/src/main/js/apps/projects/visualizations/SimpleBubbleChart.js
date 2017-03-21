/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
// @flow
import React from 'react';
import { BubbleChart } from '../../../components/charts/bubble-chart';
import { formatMeasure } from '../../../helpers/measures';
import { translate, translateWithParameters } from '../../../helpers/l10n';

type Metric = { key: string, type: string };

type Project = {
  key: string,
  measures: { [string]: string },
  name: string,
  organization?: { name: string }
};

export default class SimpleBubbleChart extends React.Component {
  props: {
    onProjectOpen: (string) => void,
    projects: Array<Project>,
    sizeMetric: Metric,
    xMetric: Metric,
    yMetric: Metric
  };

  getMetricTooltip(metric: Metric, value: number) {
    const name = translate('metric', metric.key, 'name');
    return `<div>${name}: ${formatMeasure(value, metric.type)}</div>`;
  }

  getTooltip(project: Project, x: number, y: number, size: number) {
    const fullProjectName = project.organization
      ? `<div class="little-spacer-bottom">${project.organization.name} / <strong>${project.name}</strong></div>`
      : `<div class="little-spacer-bottom"><strong>${project.name}</strong></div>`;
    const inner = [
      fullProjectName,
      this.getMetricTooltip(this.props.xMetric, x),
      this.getMetricTooltip(this.props.yMetric, y),
      this.getMetricTooltip(this.props.sizeMetric, size)
    ].join('');

    return `<div class="text-left">${inner}</div>`;
  }

  render() {
    const { xMetric, yMetric, sizeMetric } = this.props;

    const items = this.props.projects
      .filter(project => project.measures[xMetric.key] != null)
      .filter(project => project.measures[yMetric.key] != null)
      .filter(project => project.measures[sizeMetric.key] != null)
      .map(project => {
        const x = Number(project.measures[xMetric.key]);
        const y = Number(project.measures[yMetric.key]);
        const size = Number(project.measures[sizeMetric.key]);
        return {
          x,
          y,
          size,
          tooltip: this.getTooltip(project, x, y, size),
          link: project.key
        };
      });

    const formatXTick = tick => formatMeasure(tick, xMetric.type);
    const formatYTick = tick => formatMeasure(tick, yMetric.type);

    return (
      <div>
        <BubbleChart
          formatXTick={formatXTick}
          formatYTick={formatYTick}
          height={600}
          items={items}
          onBubbleClick={this.props.onProjectOpen}
          padding={[40, 20, 60, 100]}
        />
        <div className="measure-details-bubble-chart-axis x">
          {translate('metric', xMetric.key, 'name')}
        </div>
        <div className="measure-details-bubble-chart-axis y">
          {translate('metric', yMetric.key, 'name')}
        </div>
        <div className="measure-details-bubble-chart-axis size">
          {translateWithParameters(
            'component_measures.legend.size_x',
            translate('metric', sizeMetric.key, 'name')
          )}
        </div>
      </div>
    );
  }
}
