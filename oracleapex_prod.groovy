def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()
  def flagFirstBuild
  def propFileContent
  def props
  def flagPrd
  pipeline {
    agent any
    options { skipDefaultCheckout() } //skipping default checkout   
    parameters {
       booleanParam defaultValue: false, description: '', name: 'DeploySQL'
       booleanParam defaultValue: false, description: '', name: 'DeployAPEX'
       booleanParam defaultValue: false, description: '', name: 'DeployUnixScript'
       choice choices: config.environment, description: 'environment(Only for rollback)', name: 'Environment'
       string description: 'Build Number(Only for rollback)', name: 'Buildnumber', defaultValue: "default", trim: false
       booleanParam defaultValue: false, description: '', name: 'RollbackSQL'
       booleanParam defaultValue: false, description: '', name: 'RollbackAPEX'
       booleanParam defaultValue: false, description: '', name: 'RollbackUnixScript'
       booleanParam defaultValue: false, description: 'Trigger Testing Pipeline', name: 'Testing'
    }   
    environment {
      build_no = "${env.BUILD_NUMBER}"
      flagFirstBuild = "0"
      propFileContent = ""
      props = ""
      flagACC = "0"
    }      
    stages {
      	stage('Initialize') {
        	steps {
            	script {
              		// skip first build to load parameters from Jenkinsfile.
              		if (!currentBuild.getPreviousBuild()) {
                		echo "Refresh parameters only..."
                		currentBuild.result = 'UNSTABLE'
                      	flagFirstBuild = '1'
              		}
                  	else {	
                      	propFileContent = libraryResource "${config.appName}.properties"
						props = readProperties text: propFileContent
                      	initialize(props)
                    }
            	}
          	}
        }
      	stage('Rollback') {
        	when {
          		allOf {
            		expression { flagFirstBuild != '1'}
            		expression { env.RollbackSQL == 'true' || env.RollbackAPEX == 'true' || env.RollbackUnixScript == 'true'}           
          		}
        	}
        	steps {  
          		script { 
                  	rollback(config, props)
          		} 
        	}
     	} 
      	// Get source code from Bitbucket
       	stage('Checkout Source Code'){
        	when {
        		allOf {
                	expression { flagFirstBuild != '1'}
                    expression { env.Promote != 'true' } 
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }
                }
            }
        	steps {
          		script {    
                	checkoutCode(config, props) 
          		}
        	}
      	}
      	// Checks if Dependent Package is Already Installed      
        stage('Check Dependency') {
        	when {
            	allOf {
              		expression { flagFirstBuild != '1'}
                    expression { env.Promote != 'true' } 
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }
            	}
          	}
          	steps {  
            	script { 
              		checkDependency(props)
            	}
          	}
        }  
      	// Stop controlM job in Pre-Prod
        stage('Control M Job Stop: Pre-Prod') {
        	when {
          		allOf {
            		expression { flagFirstBuild != '1'} 
                    expression { config.controlM != '0' } 
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }                         
          		}
        	}
        	steps {  
          		script {
                    stopJob(config)                    
          		} 
        	}
     	}
        //SQL backup and deployment stage for Pre-Prod
      	stage('Deploy PLSQL: Pre-Prod') {
        	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { env.DeploySQL == 'true'}
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' } 
            	}
            }
            steps {
         		script {
                  	deployPLSQL(config , props, props.prePrdEnv )
                }
            }
        } 
        // Deploy APEX files in Pre-Prod
     	stage('Deploy APEX: Pre-Prod') {
        	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { env.DeployAPEX == 'true' }
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' } 
            	}
            }
            steps {
         		script {
                	deployAPEX(config, props, props.prePrdEnv)
                }
            }
        } 
      	// Deploy Unix Scripts in Pre-Prod
      	stage('Deploy Unix Scripts: Pre-Prod') {
        	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { env.DeployUnixScript == 'true' && props.shellScript == '1' }
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' } 
            	}
            }
            steps {
         		script {
                	deployUnix(props, props.prePrdEnv)
                }
            }
        }  	 
      	// Start controlM job in Pre-Prod
        stage('Control M Job Start: Pre-Prod') {
        	when {
          		allOf {
            		expression { flagFirstBuild != '1'}
                    expression { config.controlM != '0' } 
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }     
          		}
        	}
        	steps {  
          		script {
                	startJob(config)
          		} 
        	}
     	} 
      	// Nexus Upload Pre-Prod
      	stage('Nexus: Upload Artifactory-Pre-Prod'){
          	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }
                  	expression { env.DeploySQL == 'true' || env.DeployAPEX == 'true' || env.DeployUnixScript == 'true' }
          		}
        	}
        	steps{
          		script {
                	nexusUpload(config, props, props.prePrdEnv)
                  	try {
                    	def emailList = config.prdEmailApprovers
                  		def approvers = config.prdJenkinsApprovers
                  		emailext body: "Please go to ${env.BUILD_URL}.", subject: "Job ${env.JOB_NAME} (${env.BUILD_NUMBER}) is waiting for input", to: "${emailList}"
            	  		input message: "Please approve to proceed in Production ", submitterParameter: "${approvers}"
                  	}
                  	catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e)
                  	{
            			flagPrd = 'Abort'
           				return
                  	}
       			}
      		}
      	} 
      	// Stop controlM job in Production
        stage('Control M Job Stop: Production') {
        	when {
          		allOf {
            		expression { flagFirstBuild != '1'} 
                  	expression { flagPrd != 'Abort'} 
                    expression { config.controlM != '0' } 
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }                         
          		}
        	}
        	steps {  
          		script {
                    stopJob(config)  
          		} 
        	}
     	}   
      	//SQL backup and deployment stage for Production
      	stage('Deploy PLSQL: Production') {
        	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { flagPrd != 'Abort'} 
                  	expression { env.DeploySQL == 'true'}
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }   
            	}
            }
            steps {
         		script {
                    deployPLSQL(config , props, props.prdEnv )
                }
            }
        }
      	// Deploy APEX files in Production
      	stage('Deploy APEX: Production') {
        	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { flagPrd != 'Abort'}
                  	expression { env.DeployAPEX == 'true' }
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }   
            	}
            }
            steps {
         		script {
                	deployAPEX(config, props, props.prdEnv)
                }
            }
        }
      	// Deploy Unix Scripts in Production
        stage('Deploy Unix Scripts: Production') {
        	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { flagPrd != 'Abort'}
                  	expression { env.DeployUnixScript == 'true'  && props.shellScript == '1' }
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }
            	}
            }
            steps {
         		script {
                	deployUnix(props, props.prdEnv)
                }
            }
        }   
      	// Nexus upload in production
      	stage('Nexus: Promote Artifactory-Production'){
          	when {
        		allOf {
            		expression { flagFirstBuild != '1'}
                  	expression { flagPrd != 'Abort'}
                  	expression { env.DeploySQL == 'true' || env.DeployAPEX == 'true' || env.DeployUnixScript == 'true' }
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }
          		}
        	}
        	steps{
          		script {
          			nexusUpload(config, props, props.prdEnv)	
          		}
      		}
        }  
      	// Start controlM job in production
        stage('Control M Job Start: Production') {
        	when {
          		allOf {
            		expression { flagFirstBuild != '1'}
                    expression { flagPrd != 'Abort'}
                    expression { config.controlM != '0' } 
                  	expression { env.RollbackSQL != 'true' && env.RollbackAPEX != 'true' &&  env.RollbackUnixScript != 'true' }     
          		}
        	}
        	steps {  
          		script {
                	startJob(config)
          		} 
        	}
     	} 
    }
  }
}
