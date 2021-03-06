import { Component, Input } from '@angular/core';

//Models
import { NodeSummary } from '../node-summary.model';
import { NodeMemory } from '../node-memory-summary.model';
import { CheckHealthToken } from '../check-health-token.model';
import { NodeFs } from './node-fs.model';
import { HdfsUsage } from './hdfs.model';
import { Memory } from './memory.model';
//Services
import { ClusterHealthCheckService } from '../cluster-health-check.service';
import { ErrorReportingService } from '../../../shared/error/error-reporting.service';

@Component({
  selector: 'common-cluster-health-summary',
  templateUrl: 'common-cluster-health-summary.component.html',
})
export class CommonClusterHealthSummaryComponent {
  private _nodes: NodeFs[];
  private _hdfsUsage: HdfsUsage;
  private _memory: Memory;
  @Input() yarnAppsCount: number;
  isLoading: Boolean;

  constructor(private clusterHealthCheckService: ClusterHealthCheckService, private errorReportingService: ErrorReportingService) { }

  @Input()
  set checkHealthToken(checkHealthToken: CheckHealthToken) {
    if (checkHealthToken) {
      this.isLoading = true;
      this.askForClusterSnapshot(checkHealthToken);
    }
  }

  set nodes(nodes: NodeFs[]) {
    if (nodes) {
      this.isLoading = false;
      this._nodes = nodes;
    }
  }

  set hdfsUsage(hdfsUsage: HdfsUsage) {
    if (hdfsUsage) {
      this.isLoading = false;
      this._hdfsUsage = hdfsUsage;
    }
  }

  set memory(memory: Memory) {
    if (memory) {
      this.isLoading = false;
      this._memory = memory;
    }
  }

  get memory(): Memory {
    return this._memory;
  }

  get hdfsUsage(): HdfsUsage {
    return this._hdfsUsage;
  }

  get nodes(): NodeFs[] {
    return this._nodes;
  }

  private clearFields() {
    this._nodes = null;
    this._memory = null;
    this._hdfsUsage = null;
  }

  private askForClusterSnapshot(checkHealthToken: CheckHealthToken) {
    this.clearFields();
    this.askForFsClusterSnapshot(checkHealthToken);
    this.askForMemoryClusterSnapshot(checkHealthToken);
    this.askForHdfsMemoryClusterSnapshot(checkHealthToken);
  }

  private askForFsClusterSnapshot(checkHealthToken: CheckHealthToken) {
    this.clusterHealthCheckService.getFsClusterState(checkHealthToken.clusterName, checkHealthToken.token).subscribe(
      data => this.nodes = data,
      error => this.errorReportingService.reportHttpError(error)
    )
  }

  private askForHdfsMemoryClusterSnapshot(checkHealthToken: CheckHealthToken) {
    this.clusterHealthCheckService.getHdfsMemoryClusterState(checkHealthToken.clusterName, checkHealthToken.token).subscribe(
      data => this.hdfsUsage = data,
      error => this.errorReportingService.reportHttpError(error)
    )
  }

  private askForMemoryClusterSnapshot(checkHealthToken: CheckHealthToken) {
    this.clusterHealthCheckService.getMemoryClusterState(checkHealthToken.clusterName, checkHealthToken.token).subscribe(
      data => this.memory = data,
      error => this.errorReportingService.reportHttpError(error)
    )
  }
}
